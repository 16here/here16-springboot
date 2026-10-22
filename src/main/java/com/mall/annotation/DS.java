package com.mall.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据源路由注解（Phase 3 读写分离）。
 *
 * <p>标注在 Service 方法上，强制该方法的 SQL 走指定数据源：
 * <ul>
 *   <li>{@code @DS("master")}：强制主库（写/强一致性读，如支付回调、对账）；</li>
 *   <li>{@code @DS("slave")}：强制从库（纯读、可容忍秒级延迟，如订单列表、商品列表）。</li>
 * </ul>
 *
 * <p>不标注时由 {@code DataSourceAspect} 自动路由：
 * 事务内一律主库（避免主从延迟读到旧数据），无事务且方法名是读前缀（get/list/find/query…）走从库。
 *
 * <p>为什么需要：读写分离后"绝大多数读走从库、写走主库"是默认策略，
 * 但总有个别方法需要反向指定（如管理端对账必须读主库防止滞后误报），
 * 用注解显式声明，比在每个方法里写 if-else 清晰可控。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DS {
    /** 数据源 key：master / slave */
    String value();
}
