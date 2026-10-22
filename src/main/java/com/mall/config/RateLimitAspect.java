package com.mall.config;

import com.mall.common.BusinessException;
import com.mall.common.CurrentUser;
import com.mall.common.RateLimit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 限流切面（Phase 1）：拦截带 {@link RateLimit} 注解的接口。
 *
 * <p>实现：Redis 固定窗口计数（INCR + 首次设置 EXPIRE 的 Lua 脚本原子完成），
 * 窗口内计数超过 limit 即抛出 {@link BusinessException}（HTTP 429），
 * 由全局异常处理器统一返回 {@code {code:429, message:"操作太频繁"}}。
 *
 * <p>固定窗口 vs 滑动窗口：固定窗口在窗口临界点可能放过 2 倍流量
 * （前窗口末 + 后窗口初各打满一次），但实现简单、Redis 原子性好，
 * 单体加固阶段够用；需要更平滑可后续升级为 Lua 滑动窗口（已留扩展点）。
 */
@Slf4j
@Aspect
@Component
@Order(1) // 限流切面优先于其他切面（避免限流前先做了耗时的业务操作）
@RequiredArgsConstructor
public class RateLimitAspect {

    /** 限流 Redis key 前缀（与注解 key 拼接）：rl:{业务key}:{userId} */
    private static final String KEY_PREFIX = "rl:";
    /** 未登录游客的用户维度占位符 */
    private static final String GUEST = "GUEST";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 固定窗口计数 Lua 脚本（原子操作，避免并发下 INCR 与 EXPIRE 之间的竞态）：
     * <pre>
     *   count = INCR key
     *   if count == 1 then EXPIRE key windowSeconds end
     *   return count
     * </pre>
     * INCR 后首次出现说明是新窗口，顺手设置过期时间；之后只计数不续期。
     */
    private static final DefaultRedisScript<Long> INCR_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1])\n"
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n"
                    + "return c",
            Long.class
    );

    /**
     * 在目标方法执行前做限流检查：超过窗口上限直接抛异常，请求不会进入业务逻辑。
     *
     * @param joinPoint 被拦截的方法（含注解元数据）
     */
    @Before("@annotation(rateLimit)")
    public void check(JoinPoint joinPoint, RateLimit rateLimit) {
        // 1. 组装 Redis key：rl:{key}:{userId}（同用户限流；游客共用 GUEST 桶）
        Long userId = currentUserId();
        String redisKey = KEY_PREFIX + rateLimit.key() + ":" + (userId == null ? GUEST : userId);

        // 2. Lua 原子计数：返回窗口内第几次请求
        Long count = stringRedisTemplate.execute(
                INCR_SCRIPT,
                List.of(redisKey),
                String.valueOf(rateLimit.windowSeconds()));

        // 3. 超限则拦截（429），并记录告警日志便于排查刷接口
        if (count != null && count > rateLimit.limit()) {
            log.warn("接口触发限流: key={}, userId={}, count={}, limit={}",
                    rateLimit.key(), userId, count, rateLimit.limit());
            throw new BusinessException(429, rateLimit.message());
        }
    }

    /** 从 SecurityContext 取当前登录用户 ID（未登录返回 null，走游客桶） */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser current) {
            return current.userId();
        }
        return null;
    }
}
