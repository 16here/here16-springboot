package com.mall.config;

import com.mall.common.ShardingContext;
import com.mall.common.ShardingRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * MyBatis 分表拦截器（Phase 3 分表核心，v3 重写版）。
 *
 * <p><b>职责</b>：只负责 <b>SELECT</b> 的 SQL 改写（INSERT/UPDATE/DELETE 已改为
 * Repository 层显式传 {@code ${tableName}}，见各 Repository 的写方法）。改写规则：
 * <ul>
 *   <li><b>orders / order_items</b>（按 user_id % 4 分表）：
 *       业务已显式路由（ShardingContext 有后缀）→ 单表替换；
 *       无路由但有 userId 参数 → 按 userId 推导单表；
 *       无路由也无 userId（管理端列表/超时任务）→ 广播（4 张分表 UNION ALL）；</li>
 *   <li><b>user_behaviors / inventory_logs</b>（按月分表 _YYYYMM）：
 *       业务显式路由 → 指定月；否则默认当前月。</li>
 * </ul>
 *
 * <p><b>v3 为什么必须同时拦 4 参和 6 参（踩坑实录）</b>：
 * <ul>
 *   <li>v2 只拦 Executor.query 6 参，结果<b>完全不生效</b>——MyBatis 调用链是
 *       DefaultSqlSession.selectList → CachingExecutor.query(4参) → 内部
 *       {@code this.query(6参)}。而 {@code this} 是<b>真实对象</b>，不是插件代理，
 *       所以 6 参查询根本不会经过插件！</li>
 *   <li>正确做法：拦截 4 参，改写 BoundSql 后<b>显式调用代理的 6 参重载</b>
 *       （{@code executor.query(ms, parameter, rowBounds, resultHandler, key, newBoundSql)}），
 *       把改写结果真正传给 Executor；6 参签名也必须注册，否则显式调用不会被代理接收。</li>
 *   <li>显式调用会再次进入 6 参拦截，必须用 {@link #REWRITTEN} 防重入，
 *       否则已改写 SQL（orders_0）会被二次替换成 orders_0_0（雪崩）。</li>
 * </ul>
 *
 * <p><b>v3 广播为什么"条件外提"</b>：子查询只投影列 + JOIN 段，WHERE/ORDER BY/LIMIT
 * 全部提到最外层（占位符只出现一次，参数映射无需改动，分页/排序语义等价）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class,
                        ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class,
                        ResultHandler.class, CacheKey.class, BoundSql.class})
})
public class ShardingInterceptor implements Interceptor {

    private final ShardingTableManager tableManager;

    /** 防重入标志：6 参调用是 4 参显式发起的（已改写），直接放行 */
    private static final ThreadLocal<Boolean> REWRITTEN = new ThreadLocal<>();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        if (args.length == 4) {
            return interceptQuery4(invocation, args);
        }
        return interceptQuery6(invocation, args);
    }

    /**
     * 4 参 query 拦截：改写 SQL，然后显式调用 6 参重载把改写结果交给 Executor。
     * 这是唯一能真正生效的路径（4 参是 MyBatis 对外的唯一入口）。
     */
    private Object interceptQuery4(Invocation invocation, Object[] args) throws Throwable {
        MappedStatement ms = (MappedStatement) args[0];
        Object parameter = args[1];
        RowBounds rowBounds = (RowBounds) args[2];
        ResultHandler resultHandler = (ResultHandler) args[3];
        BoundSql boundSql = ms.getBoundSql(parameter);
        String sql = boundSql.getSql();

        String rewritten;
        try {
            rewritten = rewrite(sql, parameter);
        } catch (Exception e) {
            // 改写失败绝不能让业务挂掉：按原表执行 + 全量堆栈告警（宁查错不崩）
            log.warn("分表改写异常，按原表执行: sql={}, err={}", sql, e.getMessage(), e);
            return invocation.proceed();
        }
        if (rewritten == null || rewritten.equals(sql)) {
            return invocation.proceed();
        }
        // 用改写后的 BoundSql 显式调 6 参（executor 是插件代理，6 参会被本类接收）
        Executor executor = (Executor) invocation.getTarget();
        BoundSql nb = new BoundSql(ms.getConfiguration(), rewritten,
                boundSql.getParameterMappings(), boundSql.getParameterObject());
        copyAdditionalParameters(boundSql, nb);
        REWRITTEN.set(Boolean.TRUE);
        try {
            CacheKey key = executor.createCacheKey(ms, parameter, rowBounds, nb);
            return executor.query(ms, parameter, rowBounds, resultHandler, key, nb);
        } finally {
            REWRITTEN.remove();
        }
    }

    /**
     * 6 参 query 拦截：
     * <ul>
     *   <li>本线程已改写（REWRITTEN=true）→ 直接放行（防二次改写雪崩）；</li>
     *   <li>否则（外部/框架直接调 6 参的兜底）→ 改写 args[5] 替换即将执行的 BoundSql。</li>
     * </ul>
     */
    private Object interceptQuery6(Invocation invocation, Object[] args) throws Throwable {
        if (Boolean.TRUE.equals(REWRITTEN.get())) {
            return invocation.proceed();
        }
        MappedStatement ms = (MappedStatement) args[0];
        BoundSql boundSql = (BoundSql) args[5];
        String sql = boundSql.getSql();
        String rewritten;
        try {
            rewritten = rewrite(sql, args[1]);
        } catch (Exception e) {
            log.warn("分表改写异常，按原表执行: sql={}, err={}", sql, e.getMessage(), e);
            return invocation.proceed();
        }
        if (rewritten != null && !rewritten.equals(sql)) {
            BoundSql nb = new BoundSql(ms.getConfiguration(), rewritten,
                    boundSql.getParameterMappings(), boundSql.getParameterObject());
            copyAdditionalParameters(boundSql, nb);
            args[5] = nb;
        }
        return invocation.proceed();
    }

    /**
     * 复制 BoundSql 的附加参数（additionalParameters）。
     * BoundSql 没有遍历 getter，只能反射读 protected final Map（读 final 字段合法）。
     * 本分表 SQL 均未使用 <bind>，复制失败时忽略不影响执行。
     */
    private void copyAdditionalParameters(BoundSql from, BoundSql to) {
        try {
            java.lang.reflect.Field f = BoundSql.class.getDeclaredField("additionalParameters");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> extra = (Map<String, Object>) f.get(from);
            if (extra != null) {
                for (Map.Entry<String, Object> e : extra.entrySet()) {
                    to.setAdditionalParameter(e.getKey(), e.getValue());
                }
            }
        } catch (Exception ignored) {
            // 反射失败仅影响 <bind> 类动态 SQL，当前分表 SQL 均未使用，忽略即可
        }
    }

    // ==================== 改写主逻辑 ====================

    /** 返回改写后的 SQL；返回 null 表示不需要改写 */
    private String rewrite(String sql, Object param) {
        boolean hasOrders = containsTable(sql, ShardingRouter.T_ORDERS);
        boolean hasOrderItems = containsTable(sql, ShardingRouter.T_ORDER_ITEMS);
        boolean hasBehaviors = containsTable(sql, ShardingRouter.T_USER_BEHAVIORS);
        boolean hasLogs = containsTable(sql, ShardingRouter.T_INVENTORY_LOGS);
        if (!hasOrders && !hasOrderItems && !hasBehaviors && !hasLogs) {
            return null;
        }

        String out = sql;
        // 1) 订单/明细表（按用户分表）
        if (hasOrders) {
            out = rewriteOrders(out, param);
        }
        if (hasOrderItems) {
            out = rewriteOrderItems(out, param);
        }
        // 2) 行为/库存流水（按月分表）
        if (hasBehaviors) {
            out = rewriteMonthTable(out, ShardingRouter.T_USER_BEHAVIORS);
        }
        if (hasLogs) {
            out = rewriteMonthTable(out, ShardingRouter.T_INVENTORY_LOGS);
        }
        return out;
    }

    /**
     * orders 表改写：显式后缀 → 推导单表 → SELECT 广播。
     * 本拦截器只处理 SELECT（写操作在 Repository 层用 ${tableName} 显式路由）。
     */
    private String rewriteOrders(String sql, Object param) {
        String suffix = ShardingContext.suffixOf(ShardingRouter.T_ORDERS);
        if (suffix != null) {
            return replaceTable(sql, ShardingRouter.T_ORDERS, suffix);
        }
        if (ShardingContext.isOrderBroadcast()) {
            return broadcastSelect(sql, ShardingRouter.T_ORDERS);
        }
        Long userId = extractUserId(param);
        if (userId != null) {
            return replaceTable(sql, ShardingRouter.T_ORDERS,
                    "_" + (userId % ShardingRouter.ORDER_SHARD_COUNT));
        }
        // SELECT 兜底广播（管理端 JOIN 分页、定时任务等）
        return broadcastSelect(sql, ShardingRouter.T_ORDERS);
    }

    /** order_items 表改写：规则与 orders 完全一致 */
    private String rewriteOrderItems(String sql, Object param) {
        String suffix = ShardingContext.suffixOf(ShardingRouter.T_ORDER_ITEMS);
        if (suffix != null) {
            return replaceTable(sql, ShardingRouter.T_ORDER_ITEMS, suffix);
        }
        if (ShardingContext.isOrderBroadcast()) {
            return broadcastSelect(sql, ShardingRouter.T_ORDER_ITEMS);
        }
        Long userId = extractUserId(param);
        if (userId != null) {
            return replaceTable(sql, ShardingRouter.T_ORDER_ITEMS,
                    "_" + (userId % ShardingRouter.ORDER_SHARD_COUNT));
        }
        return broadcastSelect(sql, ShardingRouter.T_ORDER_ITEMS);
    }

    /**
     * 月分表改写：业务显式路由 → 指定月；否则默认当前月。
     * （建表由写路径调用方在 INSERT 前 ensureMonthTable，见各 Service）
     */
    private String rewriteMonthTable(String sql, String base) {
        String suffix = ShardingContext.suffixOf(base);
        String monthSuffix = suffix != null ? suffix : ShardingRouter.currentMonthSuffix();
        return replaceTable(sql, base, monthSuffix);
    }

    // ==================== SQL 工具 ====================

    /** SQL 是否包含某表名（词边界，防误匹配 order_status_log 等） */
    private boolean containsTable(String sql, String table) {
        return Pattern.compile("\\b" + Pattern.quote(table) + "\\b").matcher(sql).find();
    }

    /** 词边界替换表名（base → base + suffix） */
    private String replaceTable(String sql, String base, String suffix) {
        return sql.replaceAll("\\b" + Pattern.quote(base) + "\\b", base + suffix);
    }

    /**
     * 从 MyBatis 参数对象推导 userId：
     * <ul>
     *   <li>{@code Map}（@Param 注解参数）→ 取 key "userId"（数值）;</li>
     *   <li>{@code POJO} → 反射取 userId 字段。</li>
     * </ul>
     * 取不到返回 null（由调用方决定广播）。
     */
    private Long extractUserId(Object param) {
        if (param == null) {
            return null;
        }
        if (param instanceof Map<?, ?> map) {
            Object uid = map.get("userId");
            if (uid instanceof Number num) {
                return num.longValue();
            }
            return null;
        }
        try {
            java.lang.reflect.Field f = param.getClass().getDeclaredField("userId");
            f.setAccessible(true);
            Object v = f.get(param);
            if (v instanceof Number num) {
                return num.longValue();
            }
        } catch (Exception ignored) {
            // 无 userId 字段
        }
        return null;
    }

    /**
     * SELECT 广播：把单表查询改写成 4 张分表 UNION ALL，语义等价原表。
     *
     * <p>v2 采用<b>条件外提</b>结构：
     * <pre>
     * 原：SELECT o.*, u.username FROM orders o LEFT JOIN users u ... WHERE o.status=? ORDER BY o.id DESC LIMIT ?, ?
     * 新：SELECT * FROM (
     *       SELECT o.*, u.username FROM orders_0 o LEFT JOIN users u ...   ← 子查询只投影列 + JOIN
     *       UNION ALL SELECT o.*, u.username FROM orders_1 o LEFT JOIN users u ...
     *       ...
     *     ) t WHERE status=? ORDER BY id DESC LIMIT ?, ?                   ← 条件/排序/分页全部外提
     * </pre>
     *
     * <p>两个关键点：
     * <ul>
     *   <li>占位符只出现一次（外提），参数映射（ParameterMappings）无需改动，绑定天然正确；</li>
     *   <li>外层把别名前缀 {@code o.} 去掉（子查询内的别名在外层不可见），
     *       且子查询投影保持原列集（如 o.*, u.username），外层不会出现
     *       orders 与 users 的重复列（如两个 id）导致歧义。</li>
     * </ul>
     */
    private String broadcastSelect(String sql, String base) {
        String upper = sql.toUpperCase();
        String from = "FROM " + base;
        // 注意：from 是小写拼接，upper 是全大写，必须对 from 也转大写再定位，
        // 否则大小写不匹配 → fromIdx=-1 → 返回原 SQL → 广播不生效（查了空的原表）
        int fromIdx = upper.indexOf(from.toUpperCase());
        if (fromIdx < 0) {
            return sql;
        }
        String prefix = sql.substring(0, fromIdx);           // "SELECT o.*, u.username "
        String rest = sql.substring(fromIdx + from.length()); // " o LEFT JOIN ... WHERE ... ORDER BY ... LIMIT ..."
        String cols = prefix.trim();                          // "SELECT o.*, u.username"
        boolean isCount = cols.toUpperCase().equals("SELECT COUNT(*)");

        // ---- 拆分 rest：joinPart（进子查询）/ wherePart + tail（外提）----
        String restUpper = rest.toUpperCase();
        int whereIdx = restUpper.indexOf("WHERE");
        int ordIdx = restUpper.indexOf("ORDER BY");
        int limitIdx = restUpper.indexOf("LIMIT");
        int tailStart = minPositive(ordIdx, limitIdx);

        String joinPart;
        String wherePart;
        String tail;
        if (whereIdx >= 0) {
            joinPart = rest.substring(0, whereIdx);
            int wEnd = tailStart < 0 ? rest.length() : tailStart;
            wherePart = rest.substring(whereIdx, wEnd);
            tail = tailStart < 0 ? "" : rest.substring(tailStart);
        } else if (tailStart >= 0) {
            joinPart = rest.substring(0, tailStart);
            wherePart = "";
            tail = rest.substring(tailStart);
        } else {
            joinPart = rest;
            wherePart = "";
            tail = "";
        }

        // ---- 子查询投影 ----
        // 非 COUNT：保持原列集（如 "SELECT o.*, u.username"），外层列名唯一；
        // COUNT：需要外层 WHERE 能引用列，投影 "SELECT o.*"（FROM orders o 场景，别名 o 存在）
        String subCols = isCount && joinPart.trim().matches("[A-Za-z_][\\w]*")
                ? "SELECT o.*"
                : cols;

        List<String> parts = new ArrayList<>();
        for (String shard : ShardingRouter.ORDER_SHARDS) {
            parts.add(subCols + " FROM " + base + shard + joinPart);
        }
        String union = String.join(" UNION ALL ", parts);
        // 外层：去掉子查询别名前缀 o.（外层派生表 t 中不可见），条件/排序/分页外提。
        // 注意 ") t " 后必须补一个空格：wherePart 从 "WHERE" 字符开始截取（无前导空格），
        // 直接拼接会变成 ") tWHERE id = ?" 这种非法 SQL（MySQL 1064）。
        return (isCount ? "SELECT COUNT(*) FROM (" : "SELECT * FROM (")
                + union + ") t " + stripAlias(wherePart) + stripAlias(tail);
    }

    /** 去掉外层 SQL 中的表别名前缀 {@code o.}（{@code o.status → status}） */
    private String stripAlias(String s) {
        return s.replaceAll("(?<![\\w.])o\\.", "");
    }

    /** 取一组索引中第一个非负值；全负返回 -1 */
    private int minPositive(int... idxs) {
        int min = -1;
        for (int i : idxs) {
            if (i >= 0 && (min < 0 || i < min)) {
                min = i;
            }
        }
        return min;
    }
}
