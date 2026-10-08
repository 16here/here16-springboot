package com.mall.service;

import com.mall.dto.ProductListVO;
import com.mall.dto.RecommendVO;
import com.mall.entity.Product;
import com.mall.entity.ProductImage;
import com.mall.entity.UserBehavior;
import com.mall.repository.CategoryRepository;
import com.mall.repository.ProductImageRepository;
import com.mall.repository.ProductRepository;
import com.mall.repository.UserBehaviorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐服务（阶段 10）——三种推荐算法的落地与编排。
 *
 * <p>三种算法（小白版解释）：
 * <ol>
 *   <li><b>热度推荐</b>：把所有人的行为按权重累加（浏览 1 分 / 收藏 2 分 /
 *       加购 3 分 / 购买 5 分），近 N 天总分最高的商品就是"最热的商品"；
 *       未登录用户首页、以及个性化没有数据时的兜底都用它。</li>
 *   <li><b>基于物品协同过滤（item-based CF）</b>：核心思想是
 *       "行为过 A 商品的人，往往也会行为 B 商品 → A 和 B 相似"。
 *       给定商品 A，找出"行为过 A 的用户还行为过的其他商品"，
 *       共现人数越多越相似——这就是商品详情页的"相关推荐"。</li>
 *   <li><b>标签/分类推荐</b>：用户最近最关注哪个分类（行为商品里分类出现次数最多），
 *       就推那个分类的热门商品——当协同过滤候选不足时的兜底。</li>
 * </ol>
 *
 * <p>个性化推荐（首页"猜你喜欢"）的编排：协同过滤候选 →
 * 过滤掉已行为过的商品 → 不足补同分类热门 → 再不足补全局热度，
 * 保证任何用户都能拿到一屏推荐。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendService {

    private final UserBehaviorRepository behaviorRepository;
    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final CategoryRepository categoryRepository;
    private final RedisRecommendService redisRecommendService;

    /** 热度统计窗口（天，application.yml recommend.hot-days，可被管理端配置覆盖） */
    @Value("${recommend.hot-days:7}")
    private int hotDays;

    /** 首页推荐位默认数量（可被管理端配置覆盖） */
    @Value("${recommend.home-limit:8}")
    private int homeLimit;

    /** 商品详情相关推荐数量 */
    @Value("${recommend.related-limit:6}")
    private int relatedLimit;

    // ==================== 行为记录（埋点） ====================

    /**
     * 记录一条用户行为（浏览/收藏/加购/购买）。
     *
     * <p>由各业务埋点调用：ProductService 浏览时记 VIEW、
     * CartService 加购时记 CART、OrderService 支付成功时记 PURCHASE。
     * 行为记录失败绝不阻断主业务（try/catch 吞掉并记 warn）。
     */
    public void record(Long userId, Long productId, Long skuId, String type) {
        try {
            if (productId == null) {
                return;
            }
            UserBehavior behavior = new UserBehavior();
            behavior.setUserId(userId);   // NULL = 游客行为，仅参与热度统计
            behavior.setProductId(productId);
            behavior.setSkuId(skuId);
            behavior.setBehaviorType(type);
            behaviorRepository.insert(behavior);
        } catch (Exception e) {
            log.warn("记录行为失败（不影响主业务）: userId={}, productId={}, type={}, err={}",
                    userId, productId, type, e.getMessage());
        }
    }

    // ==================== 推荐入口 ====================

    /**
     * 首页推荐位（/api/recommend/home）。
     *
     * <p>已登录 → 个性化"猜你喜欢"（行为数据不够时自动兜底热度）；
     * 未登录 → 热度"热门推荐"。结果按 TTL 缓存进 Redis。
     */
    public RecommendVO home(Long userId, int limit) {
        int n = limit > 0 ? limit : configHomeLimit();
        if (userId == null) {
            // 未登录：热度"热门推荐"（结果同时回填 recommend:hot 与 recommend:home:guest
            // 两个 key——hot 供"相关/兜底"复用，guest 供游客首页并发复用）
            RecommendVO vo = cachedOrCompute(RedisRecommendService.hotKey(), "热门推荐",
                    "根据全站近 " + configHotDays() + " 天的浏览与购买热度推荐",
                    () -> hotRecommend(configHotDays(), n));
            writeCached(RedisRecommendService.homeKey(null), vo);
            return vo;
        }
        // 登录用户：优先读个性化缓存
        RecommendVO cached = readCached(RedisRecommendService.homeKey(userId), "猜你喜欢", "根据您的浏览与购买记录推荐");
        if (cached != null) {
            return cached;
        }
        RecommendVO vo = personalized(userId, n);
        writeCached(RedisRecommendService.homeKey(userId), vo);
        return vo;
    }

    /**
     * 商品详情"相关推荐"（/api/recommend/product/{id}）。
     *
     * <p>物品协同过滤：看过/买过当前商品的用户还看了什么；
     * 无相似数据时兜底同分类商品。
     */
    public RecommendVO related(Long productId, Long userId, int limit) {
        int n = limit > 0 ? limit : relatedLimit;
        RecommendVO cached = readCached(RedisRecommendService.productKey(productId, userId), "相关推荐",
                "看过此商品的用户还看了");
        if (cached != null) {
            return cached;
        }
        LocalDateTime since = nowMinusDays(configHotDays());
        // 协同过滤候选
        List<Map<String, Object>> rows = behaviorRepository.coOccurrence(productId, since, n * 2);
        List<Long> candidates = idsFromRows(rows);
        List<Long> result = new ArrayList<>(toAvailable(candidates, n, null));

        // 兜底：同分类商品（排除自身）
        if (result.size() < n) {
            Product product = productRepository.findById(productId);
            if (product != null) {
                for (Product p : productRepository.selectByCategory(product.getCategoryId(), n * 2)) {
                    if (!p.getId().equals(productId) && !result.contains(p.getId())) {
                        result.add(p.getId());
                    }
                    if (result.size() >= n) {
                        break;
                    }
                }
            }
        }
        RecommendVO vo = buildVO("相关推荐", "看过此商品的用户还看了", result);
        writeCached(RedisRecommendService.productKey(productId, userId), vo);
        return vo;
    }

    // ==================== 核心算法 ====================

    /**
     * 热度推荐：近 N 天行为权重聚合 Top N。
     */
    private List<Long> hotRecommend(int days, int limit) {
        LocalDateTime since = nowMinusDays(days);
        List<Map<String, Object>> rows = behaviorRepository.hotTop(since, limit);
        return idsFromRows(rows);
    }

    /**
     * 个性化推荐（猜你喜欢）：
     * 协同过滤候选 → 过滤已行为商品 → 兜底分类热门 → 兜底全局热度。
     */
    private RecommendVO personalized(Long userId, int limit) {
        LocalDateTime since = nowMinusDays(configHotDays());

        // 第一步：协同过滤候选（用户行为商品的相似商品，汇总计数）
        List<Map<String, Object>> rows = behaviorRepository.coOccurrenceForUser(userId, since, limit * 3);
        List<Long> candidates = idsFromRows(rows);

        // 第二步：过滤"用户已经行为过"的商品（不推已买/已加购的）
        List<Long> result = new ArrayList<>();
        for (Long id : candidates) {
            if (result.size() >= limit) {
                break;
            }
            if (behaviorRepository.countByUserAndProduct(userId, id) > 0) {
                continue;
            }
            result.add(id);
        }

        // 第三步：候选不足 → 用户最常行为的分类下的热门商品（标签/分类推荐）
        if (result.size() < limit) {
            Map<String, Object> top = behaviorRepository.topCategory(userId, since);
            Long categoryId = top == null ? null : ((Number) top.get("category_id")).longValue();
            if (categoryId != null) {
                for (Product p : productRepository.selectByCategory(categoryId, limit * 2)) {
                    if (result.size() >= limit) {
                        break;
                    }
                    if (behaviorRepository.countByUserAndProduct(userId, p.getId()) > 0 || result.contains(p.getId())) {
                        continue;
                    }
                    result.add(p.getId());
                }
            }
        }

        // 第四步：仍不足 → 全局热度补位。
        // 注意：这是最后防线，不再排除"已行为商品"——在小数据集/冷启动场景下，
        // 热门商品往往就是用户看过的商品，宁可推用户看过的热门也不给空推荐。
        if (result.size() < limit) {
            for (Long id : hotRecommend(configHotDays(), limit * 2)) {
                if (result.size() >= limit) {
                    break;
                }
                if (result.contains(id)) {
                    continue;
                }
                result.add(id);
            }
        }

        return buildVO("猜你喜欢", "根据您的浏览与购买记录推荐", result);
    }

    // ==================== 组装与缓存 ====================

    /**
     * 过滤出"上架且可展示"的商品 ID，并转成商品列表 VO。
     * 组装方式与 ProductService.query 一致：主图取第一张图片。
     */
    private List<Long> toAvailable(List<Long> ids, int limit, Long excludeId) {
        List<Long> result = new ArrayList<>();
        if (ids == null) {
            return result;
        }
        for (Long id : ids) {
            if (result.size() >= limit) {
                break;
            }
            if (excludeId != null && excludeId.equals(id)) {
                continue;
            }
            Product p = productRepository.findById(id);
            if (p != null && p.getStatus() != null && p.getStatus() == 1) {
                result.add(id);
            }
        }
        return result;
    }

    /** ID 列表 → RecommendVO（商品详情查库取最新） */
    private RecommendVO buildVO(String title, String reason, List<Long> ids) {
        RecommendVO vo = new RecommendVO();
        vo.setTitle(title);
        vo.setReason(reason);
        vo.setProducts(toProductVOs(ids));
        return vo;
    }

    /** 商品 ID 列表 → 商品列表 VO（含主图/分类名） */
    private List<ProductListVO> toProductVOs(List<Long> ids) {
        List<ProductListVO> list = new ArrayList<>();
        for (Long id : ids) {
            Product p = productRepository.findById(id);
            if (p == null) {
                continue;
            }
            ProductListVO vo = new ProductListVO();
            vo.setId(p.getId());
            vo.setSku(p.getSku());
            vo.setName(p.getName());
            vo.setPrice(p.getPrice());
            vo.setStock(p.getStock() - (p.getLockedStock() == null ? 0 : p.getLockedStock()));
            vo.setStatus(p.getStatus());
            vo.setCategoryId(p.getCategoryId());
            var category = categoryRepository.findById(p.getCategoryId());
            vo.setCategoryName(category == null ? null : category.getName());
            List<ProductImage> images = productImageRepository.selectByProductId(p.getId());
            vo.setCover(images.isEmpty() ? null : images.get(0).getImageUrl());
            list.add(vo);
        }
        return list;
    }

    /** 缓存命中则组装 VO 返回，否则执行计算并回填缓存 */
    private RecommendVO cachedOrCompute(String key, String title, String reason,
                                        java.util.function.Supplier<List<Long>> computer) {
        List<Long> cached = redisRecommendService.get(key);
        if (cached != null && !cached.isEmpty()) {
            return buildVO(title, reason, toAvailable(cached, cached.size(), null));
        }
        List<Long> ids = computer.get();
        RecommendVO vo = buildVO(title, reason, ids);
        redisRecommendService.put(key, ids);
        return vo;
    }

    /** 读个性化/相关缓存（按 userId 区分） */
    private RecommendVO readCached(String key, String title, String reason) {
        List<Long> cached = redisRecommendService.get(key);
        if (cached == null || cached.isEmpty()) {
            return null;
        }
        return buildVO(title, reason, toAvailable(cached, cached.size(), null));
    }

    /** 写个性化/相关缓存 */
    private void writeCached(String key, RecommendVO vo) {
        if (vo.getProducts() == null) {
            return;
        }
        redisRecommendService.put(key, vo.getProducts().stream().map(ProductListVO::getId).toList());
    }

    // ==================== 配置与工具 ====================

    /** 首页推荐位数量：优先 Redis 运行时配置，否则 yml 默认 */
    private int configHomeLimit() {
        String v = redisRecommendService.getConfig("homeLimit");
        return StringUtils.hasText(v) ? parseInt(v, homeLimit) : homeLimit;
    }

    /** 热度窗口天数：优先 Redis 运行时配置，否则 yml 默认 */
    private int configHotDays() {
        String v = redisRecommendService.getConfig("hotDays");
        return StringUtils.hasText(v) ? parseInt(v, hotDays) : hotDays;
    }

    private static int parseInt(String v, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static LocalDateTime nowMinusDays(int days) {
        return LocalDateTime.now().minusDays(days);
    }

    /** 统计行（Map）→ 商品 ID 有序列表（去重） */
    private static List<Long> idsFromRows(List<Map<String, Object>> rows) {
        Set<Long> seen = new HashSet<>();
        List<Long> ids = new ArrayList<>();
        if (rows == null) {
            return ids;
        }
        for (Map<String, Object> row : rows) {
            Object v = row.get("product_id");
            if (v == null) {
                continue;
            }
            long id = ((Number) v).longValue();
            if (seen.add(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    // ==================== 管理端接口支撑 ====================

    /**
     * 推荐效果统计（管理端 Dashboard 卡片）：
     * 近 N 天总行为数 / 今日行为数 / 各类行为数。
     */
    public Map<String, Object> stats(int days) {
        int d = days > 0 ? days : configHotDays();
        LocalDateTime since = nowMinusDays(d);
        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();

        long total = behaviorRepository.countTotalSince(since);
        long today = behaviorRepository.countTotalSince(todayStart);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalBehaviors", total);
        result.put("todayBehaviors", today);
        result.put("hotDays", d);
        // 各类行为数（VIEW/FAVORITE/CART/PURCHASE），SQL 按类型聚合
        long view = 0, favorite = 0, cart = 0, purchase = 0;
        for (Map<String, Object> row : behaviorRepository.countByTypeSince(since)) {
            long cnt = ((Number) row.get("cnt")).longValue();
            switch (String.valueOf(row.get("type"))) {
                case UserBehaviorRepository.TYPE_VIEW -> view = cnt;
                case UserBehaviorRepository.TYPE_FAVORITE -> favorite = cnt;
                case UserBehaviorRepository.TYPE_CART -> cart = cnt;
                case UserBehaviorRepository.TYPE_PURCHASE -> purchase = cnt;
                default -> { }
            }
        }
        result.put("viewCount", view);
        result.put("favoriteCount", favorite);
        result.put("cartCount", cart);
        result.put("purchaseCount", purchase);
        return result;
    }

    /**
     * 热门商品管理（管理端）：近 N 天每个商品的行为构成明细。
     */
    public List<Map<String, Object>> hotList(int days, int limit) {
        return behaviorRepository.behaviorDetail(nowMinusDays(days > 0 ? days : configHotDays()),
                limit > 0 ? limit : 20);
    }

    /**
     * 当前生效的推荐位配置（管理端"推荐位配置"表单回显）。
     * 优先 Redis 运行时覆盖，否则 yml 默认值。
     */
    public Map<String, Object> currentConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("homeLimit", configHomeLimit());
        cfg.put("hotDays", configHotDays());
        cfg.put("cacheTtlSeconds", parseInt(redisRecommendService.getConfig("ttlSeconds"), cacheTtlSeconds()));
        return cfg;
    }

    @Value("${recommend.cache-ttl-seconds:600}")
    private int ttlSeconds;

    private int cacheTtlSeconds() {
        return ttlSeconds;
    }

    /**
     * 保存推荐位配置（管理端）：写入 Redis 运行时配置并清除相关缓存，
     * 下一次请求立即按新配置生效（热更新，无需重启）。
     */
    public void saveConfig(Map<String, Object> config) {
        Object homeLimit = config.get("homeLimit");
        if (homeLimit != null) {
            redisRecommendService.saveConfig("homeLimit", String.valueOf(homeLimit));
        }
        Object hotDays = config.get("hotDays");
        if (hotDays != null) {
            redisRecommendService.saveConfig("hotDays", String.valueOf(hotDays));
        }
        Object ttl = config.get("cacheTtlSeconds");
        if (ttl != null) {
            redisRecommendService.saveConfig("ttlSeconds", String.valueOf(ttl));
        }
        // 配置变化 → 全部推荐缓存失效，保证立即按新配置生效
        redisRecommendService.delete(RedisRecommendService.hotKey());
        redisRecommendService.delete(RedisRecommendService.homeKey(null));
        log.info("推荐位配置已更新: {}", config);
    }
}
