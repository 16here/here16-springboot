package com.mall.repository;

import com.mall.entity.Review;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 评价数据访问层（阶段 7）。
 *
 * <p>关键设计：
 * <ul>
 *   <li>商品评价列表只查 <b>status=1（已发布）</b>，待审核/隐藏的评价用户看不到；</li>
 *   <li>评分统计按 rating 分组一次查齐 1~5 星分布 + 平均分（商品详情"评分概览"）；</li>
 *   <li>审核/回复用"条件 UPDATE（status=0）"保证只有待审核的评价能被审核，避免重复操作。</li>
 * </ul>
 */
@Mapper
public interface ReviewRepository {

    /** 新增评价（主键回填） */
    @Insert("""
            INSERT INTO reviews (order_id, order_item_id, user_id, product_id, sku_id,
                                 rating, content, images, status)
            VALUES (#{orderId}, #{orderItemId}, #{userId}, #{productId}, #{skuId},
                    #{rating}, #{content}, #{images}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Review review);

    /** 按订单明细查（校验"是否已评价"用；一个明细一条评价） */
    @Select("SELECT * FROM reviews WHERE order_item_id = #{orderItemId}")
    Review selectByOrderItemId(@Param("orderItemId") Long orderItemId);

    /** 按主键查（管理端详情/审核用） */
    @Select("""
            SELECT r.*, u.username, s.name AS sku_name
            FROM reviews r
            LEFT JOIN users u ON r.user_id = u.id
            LEFT JOIN product_skus s ON r.sku_id = s.id
            WHERE r.id = #{id}
            """)
    Review selectById(@Param("id") Long id);

    /**
     * 商品已发布评价分页（商品详情评价列表）。
     * JOIN users 带用户名、JOIN product_skus 带规格名；只展示 status=1（已发布）。
     */
    @Select("""
            SELECT r.*, u.username, s.name AS sku_name
            FROM reviews r
            LEFT JOIN users u ON r.user_id = u.id
            LEFT JOIN product_skus s ON r.sku_id = s.id
            WHERE r.product_id = #{productId} AND r.status = 1
            ORDER BY r.id DESC
            LIMIT #{offset}, #{pageSize}
            """)
    List<Review> selectPageByProduct(@Param("productId") Long productId,
                                     @Param("offset") int offset,
                                     @Param("pageSize") int pageSize);

    /** 商品已发布评价总数 */
    @Select("SELECT COUNT(*) FROM reviews WHERE product_id = #{productId} AND status = 1")
    long countByProduct(@Param("productId") Long productId);

    /**
     * 商品评分统计（评分概览）：按 rating 分组统计数量。
     * 一次查询得到 1~5 星分布，平均分由 Service 计算。
     */
    @Select("""
            SELECT rating, COUNT(*) AS cnt
            FROM reviews
            WHERE product_id = #{productId} AND status = 1
            GROUP BY rating
            """)
    List<RatingCount> selectRatingStats(@Param("productId") Long productId);

    /** 管理端评价分页（状态筛选，JOIN 用户/商品/规格） */
    @Select("""
            <script>
            SELECT r.*, u.username, p.name AS product_name, s.name AS sku_name
            FROM reviews r
            LEFT JOIN users u ON r.user_id = u.id
            LEFT JOIN products p ON r.product_id = p.id
            LEFT JOIN product_skus s ON r.sku_id = s.id
            <where>
              <if test="status != null">
                AND r.status = #{status}
              </if>
            </where>
            ORDER BY r.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Review> adminPage(@Param("status") Integer status,
                           @Param("offset") int offset,
                           @Param("pageSize") int pageSize);

    /** 管理端评价总数 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM reviews r
            <where>
              <if test="status != null">
                AND r.status = #{status}
              </if>
            </where>
            </script>
            """)
    long adminCount(@Param("status") Integer status);

    /**
     * 审核评价（审核通过 1 / 隐藏 2）。
     * 条件 UPDATE：仅待审核（0）可审核，避免重复审核。
     */
    @Update("UPDATE reviews SET status = #{status} WHERE id = #{id} AND status = 0")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /** 商家回复评价（写回复与回复时间；不改变审核状态） */
    @Update("UPDATE reviews SET reply = #{reply}, reply_at = NOW() WHERE id = #{id}")
    int updateReply(@Param("id") Long id, @Param("reply") String reply);

    /** 评分统计结果行（rating + 数量） */
    record RatingCount(Integer rating, Long cnt) {
    }
}
