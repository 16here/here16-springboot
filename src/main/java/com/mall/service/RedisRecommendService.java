package com.mall.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 推荐结果缓存服务（阶段 10）。
 *
 * <p>推荐计算涉及多次 GROUP BY 聚合（热度/协同过滤），每次实时算成本偏高，
 * 把结果按"商品 ID 列表"缓存进 Redis，TTL 内直接命中：
 * <ul>
 *   <li><b>缓存的是 ID 列表而非完整商品对象</b>：商品价格/库存/名称随时可能变化，
 *       缓存 ID、商品详情仍查库取最新，避免"缓存了过时价格"；</li>
 *   <li><b>key 规则</b>：热度 recommend:hot、
 *       首页个性化 recommend:home:&lt;userId&gt;（未登录用 guest）、
 *       商品相关 recommend:product:&lt;productId&gt;:&lt;userId&gt;；</li>
 *   <li><b>优雅降级</b>：Redis 不可用时直接返回 null（调用方实时计算），
 *       推荐功能不因 Redis 挂掉而不可用；</li>
 *   <li><b>配置可热更新</b>：管理端"推荐位配置"写入 recommend:config:* 覆盖
 *       application.yml 默认值，无需重启生效。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisRecommendService {

    private final StringRedisTemplate stringRedisTemplate;

    /** 缓存默认 TTL（秒，application.yml recommend.cache-ttl-seconds，可被管理端配置覆盖） */
    @Value("${recommend.cache-ttl-seconds:600}")
    private int cacheTtlSeconds;

    /** 热度推荐缓存 key */
    public static String hotKey() {
        return "recommend:hot";
    }

    /** 首页个性化缓存 key（未登录用户统一用 guest，只缓存热度结果） */
    public static String homeKey(Long userId) {
        return "recommend:home:" + (userId == null ? "guest" : userId);
    }

    /** 商品相关推荐缓存 key */
    public static String productKey(Long productId, Long userId) {
        return "recommend:product:" + productId + ":" + (userId == null ? "guest" : userId);
    }

    /** 管理端运行时配置 key（覆盖 application.yml 默认值，推荐位配置热更新） */
    public static String configKey(String name) {
        return "recommend:config:" + name;
    }

    /**
     * 写入推荐缓存（商品 ID 列表 → 逗号分隔字符串）。
     * Redis 异常静默跳过（不影响主流程）。
     */
    public void put(String key, List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(key, join(productIds), Duration.ofSeconds(cacheTtlSeconds));
        } catch (Exception e) {
            log.warn("推荐缓存写入失败（跳过）: key={}, err={}", key, e.getMessage());
        }
    }

    /**
     * 读取推荐缓存，命中返回商品 ID 列表，未命中/Redis 异常返回 null。
     *
     * @return 商品 ID 列表；null 表示未命中（调用方实时计算并回填）
     */
    public List<Long> get(String key) {
        try {
            String v = stringRedisTemplate.opsForValue().get(key);
            if (v == null || v.isBlank()) {
                return null;
            }
            List<Long> ids = new ArrayList<>();
            for (String part : v.split(",")) {
                try {
                    ids.add(Long.parseLong(part.trim()));
                } catch (NumberFormatException ignored) {
                    // 脏数据跳过
                }
            }
            return ids.isEmpty() ? null : ids;
        } catch (Exception e) {
            log.warn("推荐缓存读取失败（降级实时计算）: key={}, err={}", key, e.getMessage());
            return null;
        }
    }

    /** 删除缓存（行为数据变化后失效；定时任务全量刷新用） */
    public void delete(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("推荐缓存删除失败（跳过）: key={}, err={}", key, e.getMessage());
        }
    }

    /**
     * 写运行时配置（管理端推荐位配置热更新）。
     *
     * @param name  配置名（如 homeLimit / hotDays / ttlSeconds）
     * @param value 配置值
     */
    public void saveConfig(String name, String value) {
        try {
            stringRedisTemplate.opsForValue().set(configKey(name), value, Duration.ofHours(24));
        } catch (Exception e) {
            log.warn("推荐配置写入失败（跳过）: name={}, err={}", name, e.getMessage());
        }
    }

    /**
     * 读运行时配置；无则返回 null（调用方用 application.yml 默认值）。
     */
    public String getConfig(String name) {
        try {
            return stringRedisTemplate.opsForValue().get(configKey(name));
        } catch (Exception e) {
            return null;
        }
    }

    /** ID 列表 → 逗号分隔字符串 */
    private static String join(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }
}
