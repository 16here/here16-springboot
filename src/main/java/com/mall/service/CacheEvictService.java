package com.mall.service;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 缓存失效服务（Phase 1 单体加固）：负责"延迟双删"。
 *
 * <p>为什么需要延迟双删：写操作（如更新商品）会"写数据库 + 删缓存"，
 * 但存在竞态窗口——线程 A 写库后删缓存，线程 B 在删缓存之前已把旧数据
 * 重新写回缓存（读库回填），导致缓存里仍是旧值。
 * 延迟双删的解法：删完缓存后，再等 500ms 删一次，
 * 把"写库后回填的旧缓存"也清掉，第二次读请求必然回源数据库拿到新值。
 *
 * <p>双删的双层含义：
 * <ol>
 *   <li><b>删两处</b>：L1 Caffeine 本地缓存 + L2 Redis 都要删（漏删任一层都会读到旧数据）；</li>
 *   <li><b>删两次</b>：立即删 + 延迟 500ms 再删（消灭上述竞态窗口）。</li>
 * </ol>
 *
 * <p>延迟删除使用单线程调度器（任务量小、间隔固定，单线程足够且天然有序）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheEvictService {

    /** 延迟删除间隔：写库后回填旧缓存的最坏窗口通常在几十毫秒内，500ms 留足余量 */
    private static final long DELAY_MS = 500L;

    private final StringRedisTemplate stringRedisTemplate;
    private final Cache<String, String> productDetailCache;
    private final Cache<String, String> categoryTreeCache;
    /** Phase 2：RocketMQ 生产者（延迟删除优先走 MQ 延迟消息，进程重启不丢） */
    private final RocketMQProducerService rocketMQProducerService;

    /** 延迟删除调度器：单线程守护线程，仅作 MQ 不可用时的本地兜底 */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cache-delayed-evict");
                t.setDaemon(true);
                return t;
            });

    /**
     * 商品详情缓存失效（商品新增/更新/删除/上下架时调用）。
     *
     * @param productId 商品 ID（拼出 Redis key 与本地缓存 key）
     */
    public void evictProductDetail(Long productId) {
        evict("cache:product:detail:" + productId, productDetailCache);
    }

    /**
     * 分类树缓存失效（分类新增/更新/删除时调用）。
     */
    public void evictCategoryTree() {
        evict("cache:category:tree", categoryTreeCache);
    }

    /**
     * 延迟双删核心实现。
     *
     * @param redisKey  Redis key（本地缓存与 Redis 共用同一 key 字符串，简化管理）
     * @param local     Caffeine 本地缓存实例
     */
    private void evict(String redisKey, Cache<String, String> local) {
        // 1. 立即删本地缓存（同步，马上生效）
        local.invalidate(redisKey);
        // 2. 立即删 Redis（同步，马上生效）
        stringRedisTemplate.delete(redisKey);
        // 3. Phase 2：延迟 1 秒再删一次 Redis —— 优先走 RocketMQ 延迟消息
        //    （消息由 broker 持久化，应用进程重启/多实例都不会丢；消费者到点执行删除）
        boolean queued = rocketMQProducerService.sendCacheEvict(redisKey);
        if (!queued) {
            // MQ 不可用：回退本地定时线程（进程内兜底，保证"延迟再删"仍会执行）
            scheduler.schedule(() -> {
                try {
                    stringRedisTemplate.delete(redisKey);
                } catch (Exception e) {
                    // 延迟删除失败不阻塞主流程：最多多保留 1 秒旧缓存，可接受
                    log.warn("延迟删除缓存失败: key={}", redisKey, e);
                }
            }, DELAY_MS, TimeUnit.MILLISECONDS);
        }
        log.debug("缓存已双删: key={}, 延迟删走={}", redisKey, queued ? "MQ" : "本地线程");
    }
}
