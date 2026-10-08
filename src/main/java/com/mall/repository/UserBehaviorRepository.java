package com.mall.repository;

import com.mall.entity.UserBehavior;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户行为仓库（阶段 10：推荐系统）。
 *
 * <p>核心统计 SQL 说明：
 * <ul>
 *   <li><b>热度统计</b>（hotTop）：按行为权重（VIEW=1/FAVORITE=2/CART=3/PURCHASE=5）
 *       对商品打分，取近 N 天窗口内总分最高的商品——"卖得动/有人看"的天然热度；</li>
 *   <li><b>物品协同过滤</b>（coOccurrence）："行为过目标商品的用户，还行为过哪些商品"，
 *       按去重用户数（共现次数）排序——共现越多越相似，这是 item-based CF 的简化落地；</li>
 *   <li><b>用户个性化</b>（coOccurrenceForUser）：用户行为过的每个商品各找相似商品，
 *       汇总计数排序——近似"猜你喜欢"的候选集，最终由 RecommendService 过滤
 *       已行为商品并兜底分类推荐。</li>
 * </ul>
 */
@Mapper
public interface UserBehaviorRepository {

    /** 行为类型常量（与表注释一致） */
    String TYPE_VIEW = "VIEW";
    String TYPE_FAVORITE = "FAVORITE";
    String TYPE_CART = "CART";
    String TYPE_PURCHASE = "PURCHASE";

    /**
     * 记录一条行为（浏览/收藏/加购/购买）。
     * 幂等性由业务层保证（如重复浏览允许累加，但加购只记一次由调用方控制）。
     */
    @Insert("""
            INSERT INTO user_behaviors (user_id, product_id, sku_id, behavior_type)
            VALUES (#{userId}, #{productId}, #{skuId}, #{behaviorType})
            """)
    int insert(UserBehavior behavior);

    /**
     * 热度推荐：近 {@code days} 天按行为权重聚合，取总分 Top N。
     *
     * <p>权重映射用 CASE WHEN 写在 SQL 里，一次查询出结果。
     *
     * @return 每行 map 含 product_id / score
     */
    @Select("""
            SELECT product_id, SUM(
                CASE behavior_type
                    WHEN 'VIEW' THEN 1
                    WHEN 'FAVORITE' THEN 2
                    WHEN 'CART' THEN 3
                    WHEN 'PURCHASE' THEN 5
                    ELSE 0
                END
            ) AS score
            FROM user_behaviors
            WHERE created_at >= #{since}
            GROUP BY product_id
            ORDER BY score DESC, MAX(created_at) DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> hotTop(@Param("since") LocalDateTime since, @Param("limit") int limit);

    /**
     * 物品协同过滤：与目标商品"相似"的商品（相关推荐）。
     *
     * <p>语义：行为过商品 A 的所有用户，他们还行为过哪些其他商品；
     * 按去重用户数（共现次数）降序——行为过 A 的人越多人也行为过 B，B 与 A 越相似。
     */
    @Select("""
            SELECT b2.product_id, COUNT(DISTINCT b2.user_id) AS cnt
            FROM user_behaviors b1
            JOIN user_behaviors b2 ON b1.user_id = b2.user_id AND b2.product_id <> #{productId}
            WHERE b1.product_id = #{productId}
              AND b1.user_id IS NOT NULL
              AND b2.user_id IS NOT NULL
              AND b1.created_at >= #{since}
            GROUP BY b2.product_id
            ORDER BY cnt DESC, MAX(b2.created_at) DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> coOccurrence(@Param("productId") Long productId,
                                           @Param("since") LocalDateTime since,
                                           @Param("limit") int limit);

    /**
     * 用户个性化候选：用户行为过的商品各自找相似商品，汇总计数（猜你喜欢候选集）。
     *
     * <p>语义：b1 = 目标用户的全部行为记录；b2 = 每个行为商品的"相似商品"
     * （与行为该商品的人共现的商品）；按共现用户数汇总排序，
     * 得到"和用户喜欢过的东西最接近的其他商品"。
     */
    @Select("""
            SELECT b2.product_id, COUNT(DISTINCT b2.user_id) AS cnt
            FROM user_behaviors b1
            JOIN user_behaviors b2 ON b1.user_id = b2.user_id AND b2.product_id <> b1.product_id
            WHERE b1.user_id = #{userId}
              AND b2.user_id IS NOT NULL
              AND b2.created_at >= #{since}
            GROUP BY b2.product_id
            ORDER BY cnt DESC, MAX(b2.created_at) DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> coOccurrenceForUser(@Param("userId") Long userId,
                                                  @Param("since") LocalDateTime since,
                                                  @Param("limit") int limit);

    /**
     * 用户最常行为的分类（标签/分类推荐兜底用）。
     *
     * <p>语义：把用户行为过的商品 JOIN 商品表拿到分类，
     * 统计出现次数最多的分类——"用户最近最关注哪一类"。
     */
    @Select("""
            SELECT p.category_id, COUNT(*) AS cnt
            FROM user_behaviors b
            JOIN products p ON b.product_id = p.id
            WHERE b.user_id = #{userId} AND b.created_at >= #{since}
            GROUP BY p.category_id
            ORDER BY cnt DESC
            LIMIT 1
            """)
    Map<String, Object> topCategory(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /** 近 N 天按类型统计行为数（管理端推荐效果统计，返回 type + cnt） */
    @Select("""
            SELECT behavior_type AS type, COUNT(*) AS cnt
            FROM user_behaviors
            WHERE created_at >= #{since}
            GROUP BY behavior_type
            """)
    List<Map<String, Object>> countByTypeSince(@Param("since") LocalDateTime since);

    /** 近 N 天行为总数（管理端推荐效果统计） */
    @Select("""
            SELECT COUNT(*) FROM user_behaviors WHERE created_at >= #{since}
            """)
    long countTotalSince(@Param("since") LocalDateTime since);

    /** 用户是否已行为过某商品（个性化推荐排除"已买/已加购"用） */
    @Select("""
            SELECT COUNT(*) FROM user_behaviors
            WHERE user_id = #{userId} AND product_id = #{productId}
            """)
    int countByUserAndProduct(@Param("userId") Long userId, @Param("productId") Long productId);

    /**
     * 管理端热门商品管理：近 N 天每个商品的行为构成明细。
     *
     * <p>按商品分组统计 VIEW/FAVORITE/CART/PURCHASE 四类次数与总权重分，
     * 展示"这个商品为什么热"。
     */
    @Select("""
            SELECT b.product_id AS productId, p.name AS productName,
                   SUM(CASE WHEN b.behavior_type = 'VIEW' THEN 1 ELSE 0 END)     AS viewCount,
                   SUM(CASE WHEN b.behavior_type = 'FAVORITE' THEN 1 ELSE 0 END) AS favoriteCount,
                   SUM(CASE WHEN b.behavior_type = 'CART' THEN 1 ELSE 0 END)     AS cartCount,
                   SUM(CASE WHEN b.behavior_type = 'PURCHASE' THEN 1 ELSE 0 END) AS purchaseCount,
                   SUM(CASE b.behavior_type
                       WHEN 'VIEW' THEN 1 WHEN 'FAVORITE' THEN 2
                       WHEN 'CART' THEN 3 WHEN 'PURCHASE' THEN 5 ELSE 0 END)     AS score
            FROM user_behaviors b
            JOIN products p ON b.product_id = p.id
            WHERE b.created_at >= #{since}
            GROUP BY b.product_id, p.name
            ORDER BY score DESC, MAX(b.created_at) DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> behaviorDetail(@Param("since") LocalDateTime since, @Param("limit") int limit);

    /**
     * 取消收藏（收藏功能，阶段 11 需求 3）：
     * 删除某用户对某商品的一条 FAVORITE 行为记录。
     * 未收藏时删除 0 行，业务层视为幂等成功。
     */
    @Delete("""
            DELETE FROM user_behaviors
            WHERE user_id = #{userId} AND product_id = #{productId} AND behavior_type = #{type}
            """)
    int deleteByUserAndProductAndType(@Param("userId") Long userId,
                                      @Param("productId") Long productId,
                                      @Param("type") String type);

    /**
     * 用户收藏的商品 ID 列表（收藏夹）。
     * 按收藏时间倒序（同商品多次收藏取最近一次时间）。
     */
    @Select("""
            SELECT product_id FROM user_behaviors
            WHERE user_id = #{userId} AND behavior_type = #{type}
            GROUP BY product_id
            ORDER BY MAX(created_at) DESC
            """)
    List<Long> selectProductIdsByUserAndType(@Param("userId") Long userId, @Param("type") String type);

    /**
     * 判断用户是否已收藏某商品（详情页收藏按钮状态）。
     */
    @Select("""
            SELECT COUNT(*) FROM user_behaviors
            WHERE user_id = #{userId} AND product_id = #{productId} AND behavior_type = #{type}
            """)
    int countByUserAndProductAndType(@Param("userId") Long userId,
                                     @Param("productId") Long productId,
                                     @Param("type") String type);
}
