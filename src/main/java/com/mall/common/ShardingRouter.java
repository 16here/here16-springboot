package com.mall.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 分表路由工具（Phase 3 分表）。
 *
 * <p>业务代码通过本工具声明"接下来这批 SQL 要访问哪张分表"：
 * <ul>
 *   <li>{@link #routeOrdersByUser(Long)}：订单/明细按用户分表（user_id % 4）；
 *       带 userId 的订单查询/下单/状态流转前调用；</li>
 *   <li>{@link #routeOrdersBroadcast()}：管理端/定时任务跨全部分表（UNION ALL）；
 *       查完再按具体订单切回单表（见 OrderService.ship/close 等）；</li>
 *   <li>{@link #routeMonth(String, LocalDateTime)}：行为/库存流水按月分表（_YYYYMM）。</li>
 * </ul>
 *
 * <p>路由信息线程内生效（ThreadLocal），HTTP 请求结束由
 * {@code ShardingContextFilter} 统一清理；定时任务/消费者方法自行 try/finally 清理。
 *
 * <p>为什么路由粒度是"表 + 后缀"而不是"SQL 参数"：分表规则属于业务语义
 * （user_id 归谁、按哪个月分），由业务代码显式声明最可靠；拦截器只做
 * 机械的表名改写，两者职责分离。
 */
public final class ShardingRouter {

    /** 订单分表数量（user_id % 4） */
    public static final int ORDER_SHARD_COUNT = 4;

    /** 订单分表后缀列表（广播 UNION ALL 用） */
    public static final String[] ORDER_SHARDS = {"_0", "_1", "_2", "_3"};

    /** 基础表名常量 */
    public static final String T_ORDERS = "orders";
    public static final String T_ORDER_ITEMS = "order_items";
    public static final String T_USER_BEHAVIORS = "user_behaviors";
    public static final String T_INVENTORY_LOGS = "inventory_logs";

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private ShardingRouter() {
    }

    /** 按用户路由订单与明细表（user_id % 4） */
    public static void routeOrdersByUser(Long userId) {
        long shard = (userId == null ? 0L : userId) % ORDER_SHARD_COUNT;
        String suffix = "_" + shard;
        ShardingContext.setSuffix(T_ORDERS, suffix);
        ShardingContext.setSuffix(T_ORDER_ITEMS, suffix);
    }

    /** 订单表广播模式（管理端列表/对账/超时任务跨全部分表查询） */
    public static void routeOrdersBroadcast() {
        ShardingContext.setOrderBroadcast();
    }

    /** 按时间路由月分表（user_behaviors / inventory_logs → _yyyyMM） */
    public static void routeMonth(String baseTable, LocalDateTime time) {
        ShardingContext.setSuffix(baseTable, "_" + (time == null
                ? LocalDateTime.now().format(MONTH) : time.format(MONTH)));
    }

    /** 当前月后缀（拦截器"默认月表"用） */
    public static String currentMonthSuffix() {
        return "_" + LocalDateTime.now().format(MONTH);
    }

    /**
     * 计算某用户的订单分表后缀（user_id % 4，如 userId=5 → "_1"）。
     * 写操作（INSERT/UPDATE/DELETE）的表名由 Repository 层显式拼表名时使用。
     */
    public static String shardSuffix(Long userId) {
        long shard = (userId == null ? 0L : userId) % ORDER_SHARD_COUNT;
        return "_" + shard;
    }

    /**
     * 拼实际分表名（base + suffix）。
     * <p>用途：写操作的 {@code ${tableName}} 参数——业务层先 route（ShardingContext
     * 已有后缀），再取后缀拼表名传给 Repository，保证 INSERT/UPDATE/DELETE
     * 落在正确的分表上。
     * <p>后缀为 null（业务漏路由）时返回基础表名，执行时 MySQL 报
     * "table doesn't exist" 快速暴露漏路由，避免静默写错表。
     */
    public static String tableName(String base, String suffix) {
        return (suffix == null || suffix.isEmpty()) ? base : base + suffix;
    }

    /** 从当前线程路由上下文取表名（调用方已 route 的场景） */
    public static String routedTableName(String base) {
        return tableName(base, ShardingContext.suffixOf(base));
    }

    /** 清空当前线程分表路由（任务/消费者 finally 调用） */
    public static void clear() {
        ShardingContext.clear();
    }
}
