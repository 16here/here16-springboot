package com.mall.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 幂等守卫服务（Phase 1 单体加固）。
 *
 * <p>解决"重复提交"问题：前端在下单等写操作时生成一个全局唯一的 requestId
 * （UUID），后端以此 requestId + 业务主体（如 userId）拼成 Redis key，
 * 用 SETNX 原子占位——第一个请求占位成功正常执行业务，
 * 后续重复请求占位失败直接拒绝，从而保证同一个请求只被处理一次。
 *
 * <p>适用场景：
 * <ul>
 *   <li>下单：用户双击"提交订单"、网络超时重试，可能重复创建订单；</li>
 *   <li>支付回调：支付平台会重试通知（支付宝/微信最多 24 小时重试 8 次）。
 *       本项目支付回调的幂等由"条件 UPDATE 流水"保证（重复回调 rows=0 直接返回成功），
 *       幂等键方案与其互补，覆盖"回调重试期间并发到达"的窗口。</li>
 * </ul>
 *
 * <p>实现要点：SETNX 返回 true 才允许继续；TTL 到期后 key 自动释放，
 * 意味着超过 TTL 的重复请求会被当作新请求——下单 TTL 设 5 分钟
 * （正常一次下单远小于该时间，超过 5 分钟必然是新一轮操作）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentService {

    /** 幂等 key 前缀 */
    private static final String KEY_PREFIX = "idem:";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 尝试占位：占位成功返回 true（首次请求，可以执行业务）；
     * 占位失败返回 false（重复请求，应直接拒绝并提示用户）。
     *
     * @param bizKey 业务标识，如 {@code order:user:1}（调用方拼好）
     * @param ttlSeconds key 存活时间（秒），超过视为新一轮请求
     */
    public boolean tryAcquire(String bizKey, long ttlSeconds) {
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + bizKey, "1", ttlSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 主动释放占位（业务失败回滚时调用，允许用户立即重试）。
     *
     * @param bizKey 与 {@link #tryAcquire} 相同的业务标识
     */
    public void release(String bizKey) {
        stringRedisTemplate.delete(KEY_PREFIX + bizKey);
    }
}
