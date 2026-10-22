package com.mall.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 本地缓存配置（Phase 1 单体加固）。
 *
 * <p>为"商品详情"与"分类树"两个高频读接口各准备一个 Caffeine 本地缓存，
 * 作为两级缓存中的 L1（进程内），与 Redis L2 组成：
 * <pre>
 *   Controller → L1 Caffeine（微秒级）→ L2 Redis（毫秒级）→ MySQL（业务权威）
 * </pre>
 *
 * <p>为什么 L1 用 Caffeine 而不是只用 Redis：
 * 商品详情/分类树是读多写少的热点数据，每次请求都走 Redis 仍有网络开销；
 * 本地缓存命中在微秒级，可显著降低 Redis 与数据库压力。
 * 代价是"多实例各自持有一份"，因此 L1 只缓存 60 秒且由"延迟双删"保证一致性
 * （详见 {@code CacheEvictService}），单体阶段单实例部署不存在数据分裂问题。
 */
@Configuration
public class CacheConfig {

    /**
     * 商品详情本地缓存：最多 2000 条，写入 60 秒后过期。
     * value 直接存 JSON 字符串（与 Redis 层共用序列化格式）。
     */
    @Bean
    public Cache<String, String> productDetailCache() {
        return Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 分类树本地缓存：数量少但读取极频繁，写入 120 秒后过期。
     */
    @Bean
    public Cache<String, String> categoryTreeCache() {
        return Caffeine.newBuilder()
                .maximumSize(50)
                .expireAfterWrite(120, TimeUnit.SECONDS)
                .build();
    }
}
