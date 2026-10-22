package com.mall.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解（Phase 1 单体加固）。
 *
 * <p>标注在 Controller 方法上，由 {@code RateLimitAspect} 拦截：
 * 以 Redis Lua 原子计数实现"固定窗口"限流，key 维度 = 注解 key 前缀 + 当前用户 ID
 * （未登录用户以 GUEST 占位，同一 IP 维度的游客共享一个计数桶）。
 *
 * <p>用法示例：下单接口限制每用户每分钟 20 次：
 * <pre>{@code
 * @RateLimit(key = "order:create", limit = 20, windowSeconds = 60)
 * @PostMapping
 * public Result<OrderVO> create(...) { ... }
 * }</pre>
 *
 * <p>为什么选 Redis 计数而非本地令牌桶：
 * 单体阶段 Redis 已存在（购物车/库存/推荐都在用），计数状态放 Redis 便于
 * 将来多实例部署时共享同一份限流数据，不用改任何代码即可横向扩展。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 限流 key 前缀（Redis key 最终形如 {@code rl:{key}:{userId}}）。
     * 建议按"业务:动作"命名，如 order:create、product:search、coupon:receive。
     */
    String key();

    /** 窗口内允许的最大请求次数（QPS 上限 = limit / windowSeconds） */
    int limit() default 30;

    /** 计数窗口（秒），默认 60 秒一个窗口 */
    int windowSeconds() default 60;

    /** 超过限制时返回的错误提示文案 */
    String message() default "操作太频繁，请稍后再试";
}
