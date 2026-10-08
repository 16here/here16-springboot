package com.mall.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis 库存服务：用 Redis 做"预减库存"，在数据库扣减之前快速拦截超卖请求，
 * 并给前端提供实时可卖库存（读 Redis，避免每次查库）。
 *
 * <p>设计说明（阶段 6）：
 * <ul>
 *   <li><b>预减是"快速拦截"，不是最终防线</b>：Redis 减成功后仍要过数据库条件 UPDATE
 *       （stock - locked_stock >= qty），数据库原子保证不超卖；Redis 不一致/宕机时
 *       最终仍由数据库兜底；</li>
 *   <li><b>Lua 原子预减</b>：decrby 后为负数立即回补并返回失败，整个判断原子完成，
 *       并发下单不会出现"超卖判定"竞态；</li>
 *   <li><b>优雅降级</b>：Redis 不可用（连接异常/未启动）时记 warn 日志并跳过预减，
 *       <b>绝不让库存/下单功能因 Redis 挂掉而不可用</b>（数据库仍是唯一权威）；</li>
 *   <li><b>key 规则</b>：SKU 级 <code>stock:sku:&lt;skuId&gt;</code>，
 *       商品级（无规格）<code>stock:product:&lt;productId&gt;</code>。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStockService {

    private final StringRedisTemplate stringRedisTemplate;

    /** Lua 原子预减脚本：足够则减并返回剩余值；不足则回补并返回 -1 */
    private static final DefaultRedisScript<Long> PRE_DEDUCT_SCRIPT = new DefaultRedisScript<>(
            """
            local v = redis.call('decrby', KEYS[1], ARGV[1])
            if v < 0 then
              redis.call('incrby', KEYS[1], ARGV[1])
              return -1
            end
            return v
            """, Long.class);

    /** SKU 级 key */
    public static String skuKey(Long skuId) {
        return "stock:sku:" + skuId;
    }

    /** 商品级 key（无规格商品） */
    public static String productKey(Long productId) {
        return "stock:product:" + productId;
    }

    /**
     * 原子预减库存（下单锁库存前调用，防超卖的"第一道闸"）。
     *
     * @param key 库存 key（skuKey/productKey）
     * @param qty 预减数量
     * @return true=预减成功（可继续）；false=库存不足（拒绝下单）；
     *         Redis 不可用时降级返回 true（交由数据库兜底）
     */
    public boolean preDeduct(String key, int qty) {
        try {
            if (!stringRedisTemplate.hasKey(key)) {
                // key 不存在（如服务重启后未预热）：不拦截，数据库条件是最终防线
                return true;
            }
            Long rest = stringRedisTemplate.execute(PRE_DEDUCT_SCRIPT, List.of(key), String.valueOf(qty));
            if (rest != null && rest < 0) {
                log.debug("Redis 预减库存不足: key={}, qty={}", key, qty);
                return false;
            }
            return true;
        } catch (Exception e) {
            // 优雅降级：Redis 异常不阻断下单，数据库条件 UPDATE 兜底
            log.warn("Redis 预减库存异常（降级放行，由数据库兜底）: key={}, err={}", key, e.getMessage());
            return true;
        }
    }

    /**
     * 回补库存（下单失败回滚 / 订单取消/关闭/超时释放时调用）。
     * Redis 不可用时静默跳过（key 不在时也无所谓）。
     */
    public void rollback(String key, int qty) {
        try {
            stringRedisTemplate.opsForValue().increment(key, qty);
        } catch (Exception e) {
            log.warn("Redis 回补库存异常（跳过）: key={}, err={}", key, e.getMessage());
        }
    }

    /**
     * 刷新指定 key 的库存值（数据库库存变化后同步，管理端调整时调用）。
     */
    public void refresh(String key, int available) {
        try {
            stringRedisTemplate.opsForValue().set(key, String.valueOf(Math.max(0, available)));
        } catch (Exception e) {
            log.warn("Redis 刷新库存异常（跳过）: key={}, err={}", key, e.getMessage());
        }
    }

    /**
     * 读取实时可卖库存：优先 Redis，key 不存在则回源数据库并写入 Redis（预热）。
     *
     * @param key         库存 key
     * @param dbAvailable 数据库计算出的可卖库存（stock - locked_stock）
     */
    public int getAvailable(String key, int dbAvailable) {
        try {
            String v = stringRedisTemplate.opsForValue().get(key);
            if (v != null) {
                return Math.max(0, Integer.parseInt(v));
            }
            refresh(key, dbAvailable);
        } catch (Exception e) {
            log.warn("Redis 读库存异常（回源数据库）: key={}, err={}", key, e.getMessage());
        }
        return Math.max(0, dbAvailable);
    }
}
