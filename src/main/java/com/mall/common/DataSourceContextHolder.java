package com.mall.common;

/**
 * 数据源上下文（Phase 3 读写分离）。
 *
 * <p>用 ThreadLocal 保存"当前线程接下来 SQL 应该走哪个数据源"的 key
 * （master / slave），由 {@code DataSourceAspect} 在 Service 方法进入时设置、
 * 方法退出时清除；{@code DynamicDataSource} 在每次获取连接时读取并路由。
 *
 * <p>为什么用 ThreadLocal：一次 HTTP 请求只由一个线程处理，读写分离的路由
 * 决策只需要"当前线程"知道即可，不需要跨线程传递；请求结束由
 * {@code DataSourceContextHolder.clear()} 清理，避免线程池复用串号。
 */
public final class DataSourceContextHolder {

    /** 主库 key（写/强一致性读） */
    public static final String MASTER = "master";
    /** 从库 key（读/可容忍延迟） */
    public static final String SLAVE = "slave";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private DataSourceContextHolder() {
    }

    /** 设置当前线程数据源 key（master / slave） */
    public static void set(String ds) {
        HOLDER.set(ds);
    }

    /** 读取当前线程数据源 key（null 表示未设置，由 DynamicDataSource 走默认策略） */
    public static String get() {
        return HOLDER.get();
    }

    /** 清除当前线程数据源 key（方法结束/请求结束必须调用，防止线程复用串号） */
    public static void clear() {
        HOLDER.remove();
    }
}
