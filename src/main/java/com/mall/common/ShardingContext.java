package com.mall.common;

import java.util.HashMap;
import java.util.Map;

/**
 * 分表路由上下文（Phase 3 分表）。
 *
 * <p>用 ThreadLocal 保存"当前线程接下来的 SQL 要把哪些表改写到哪张分表"，
 * 由 {@code ShardingRouter} 设置、{@code ShardingContextFilter}（HTTP 请求）
 * 与各定时任务/消费者方法在结束时清理。
 *
 * <p>为什么用 ThreadLocal：一次请求/一个任务只在一个线程内执行，
 * 分表路由信息只需线程内可见；请求结束必须清理，否则线程池复用会把
 * 上一个请求的路由（如 orders_0）串到下一个请求。
 *
 * <p>两个维度：
 * <ul>
 *   <li>{@code tableSuffix}：表名 → 后缀（如 orders → "_0"、user_behaviors → "_202610"）；</li>
 *   <li>{@code orderBroadcast}：orders 广播标志（管理端/定时任务跨全部分表 UNION ALL 查询）。</li>
 * </ul>
 */
public final class ShardingContext {

    /** 表名 → 分表后缀（如 "orders" → "_0"） */
    private static final ThreadLocal<Map<String, String>> TABLE_SUFFIX = new ThreadLocal<>();

    /** orders 广播标志：true 表示 orders 查询要跨 4 张分表 UNION ALL */
    private static final ThreadLocal<Boolean> ORDER_BROADCAST = new ThreadLocal<>();

    private ShardingContext() {
    }

    /** 设置某张表的后缀（覆盖旧值） */
    public static void setSuffix(String baseTable, String suffix) {
        Map<String, String> map = TABLE_SUFFIX.get();
        if (map == null) {
            map = new HashMap<>();
            TABLE_SUFFIX.set(map);
        }
        map.put(baseTable, suffix);
    }

    /** 读取某张表的后缀；未设置返回 null（由拦截器决定默认策略） */
    public static String suffixOf(String baseTable) {
        Map<String, String> map = TABLE_SUFFIX.get();
        return map == null ? null : map.get(baseTable);
    }

    /** 清除某张表的显式后缀（如从单表切到广播前） */
    public static void removeSuffix(String baseTable) {
        Map<String, String> map = TABLE_SUFFIX.get();
        if (map != null) {
            map.remove(baseTable);
        }
    }

    /** 设置 orders 广播（跨 4 张分表查询），同时清掉 orders 单表后缀 */
    public static void setOrderBroadcast() {
        removeSuffix("orders");
        removeSuffix("order_items");
        ORDER_BROADCAST.set(Boolean.TRUE);
    }

    /** 是否处于 orders 广播模式 */
    public static boolean isOrderBroadcast() {
        return Boolean.TRUE.equals(ORDER_BROADCAST.get());
    }

    /** 清空当前线程全部路由信息（请求/任务结束必须调用） */
    public static void clear() {
        TABLE_SUFFIX.remove();
        ORDER_BROADCAST.remove();
    }
}
