package com.mall.repository;

import com.mall.entity.Cart;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 购物车数据访问层。
 *
 * <p>安全约定：所有按 id 的更新/删除都同时带 userId 条件
 * （WHERE id = ? AND user_id = ?），影响行数为 0 即代表"不存在或不属于该用户"，
 * 从数据层杜绝越权操作他人购物车。
 */
@Mapper
public interface CartRepository {

    /** 按用户 + 规格查购物车项（添加时做"合并累加"判断用） */
    @Select("SELECT * FROM carts WHERE user_id = #{userId} AND sku_id = #{skuId}")
    Cart findByUserIdAndSkuId(@Param("userId") Long userId, @Param("skuId") Long skuId);

    /**
     * 查询某用户的完整购物车（JOIN 商品与规格，带出展示所需字段）。
     *
     * <p>说明：JOIN products 采用内连接，商品被物理删除后其购物车记录
     * 不再展示（残留记录可后续清理）；下架商品仍展示（前端可标记），
     * 结算环节再做状态校验。
     */
    @Select("""
            SELECT c.*,
                   p.name  AS product_name,
                   p.status AS product_status,
                   (p.stock - p.locked_stock) AS product_stock,
                   p.price AS product_price,
                   (SELECT image_url FROM product_images
                     WHERE product_id = c.product_id
                     ORDER BY sort, id LIMIT 1) AS product_image,
                   s.name  AS sku_name,
                   s.price AS sku_price,
                   (s.stock - s.locked_stock) AS sku_stock
            FROM carts c
            JOIN products p ON c.product_id = p.id
            LEFT JOIN product_skus s ON c.sku_id = s.id AND c.sku_id > 0
            WHERE c.user_id = #{userId}
            ORDER BY c.created_at DESC, c.id DESC
            """)
    List<Cart> selectByUserId(@Param("userId") Long userId);

    /** 新增购物车项（自增主键回填） */
    @Insert("""
            INSERT INTO carts (user_id, product_id, sku_id, quantity)
            VALUES (#{userId}, #{productId}, #{skuId}, #{quantity})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Cart cart);

    /** 更新数量（带 userId 防越权；rows=0 表示不存在或不属于该用户） */
    @Update("UPDATE carts SET quantity = #{quantity} WHERE id = #{id} AND user_id = #{userId}")
    int updateQuantity(@Param("id") Long id, @Param("userId") Long userId, @Param("quantity") Integer quantity);

    /** 删除单项（带 userId 防越权） */
    @Delete("DELETE FROM carts WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 清空某用户的购物车 */
    @Delete("DELETE FROM carts WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);
}
