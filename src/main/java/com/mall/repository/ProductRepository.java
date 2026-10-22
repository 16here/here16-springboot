package com.mall.repository;

import com.mall.dto.ProductQueryDTO;
import com.mall.entity.Product;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商品数据访问层（MyBatis Mapper，注解动态 SQL）。
 *
 * <p>说明：
 * <ul>
 *   <li>分页条件：关键词（名称/SKU 模糊）、分类筛选、状态过滤，动态拼接 WHERE；</li>
 *   <li>排序：{@code orderBy} 必须由 Service 层从白名单映射后传入（禁止直接拼接用户输入，
 *       避免 SQL 注入）；</li>
 *   <li>列表查询 LEFT JOIN categories 带出分类名，填充到 Product.categoryName（非表字段）。</li>
 * </ul>
 */
@Mapper
public interface ProductRepository {

    /**
     * 分页查询商品（带分类名）。
     *
     * @param dto      查询条件（关键词/分类）
     * @param status   状态过滤；null 表示不过滤（管理端查全部）
     * @param orderBy  排序 SQL 片段（Service 层白名单映射后传入）
     * @param offset   分页偏移量
     * @param pageSize 每页数量
     */
    @Select("""
            <script>
            SELECT p.*, c.name AS category_name
            FROM products p
            LEFT JOIN categories c ON p.category_id = c.id
            <where>
              <if test="dto.keyword != null and dto.keyword != ''">
                AND (p.name LIKE CONCAT('%', #{dto.keyword}, '%')
                     OR p.sku LIKE CONCAT('%', #{dto.keyword}, '%'))
              </if>
              <if test="dto.categoryId != null">
                AND p.category_id IN (
                    SELECT id FROM categories
                    WHERE id = #{dto.categoryId} OR parent_id = #{dto.categoryId}
                )
              </if>
              <if test="status != null">
                AND p.status = #{status}
              </if>
            </where>
            ORDER BY ${orderBy}
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Product> selectPage(@Param("dto") ProductQueryDTO dto,
                             @Param("status") Integer status,
                             @Param("orderBy") String orderBy,
                             @Param("offset") int offset,
                             @Param("pageSize") int pageSize);

    /**
     * 统计符合条件的商品总数（与 selectPage 条件保持一致）。
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM products p
            <where>
              <if test="dto.keyword != null and dto.keyword != ''">
                AND (p.name LIKE CONCAT('%', #{dto.keyword}, '%')
                     OR p.sku LIKE CONCAT('%', #{dto.keyword}, '%'))
              </if>
              <if test="dto.categoryId != null">
                AND p.category_id IN (
                    SELECT id FROM categories
                    WHERE id = #{dto.categoryId} OR parent_id = #{dto.categoryId}
                )
              </if>
              <if test="status != null">
                AND p.status = #{status}
              </if>
            </where>
            </script>
            """)
    long count(@Param("dto") ProductQueryDTO dto, @Param("status") Integer status);

    /** 按主键查询（不含分类名） */
    @Select("SELECT * FROM products WHERE id = #{id}")
    Product findById(@Param("id") Long id);

    /** 按 SKU 编码查询（创建/编辑时唯一性校验） */
    @Select("SELECT * FROM products WHERE sku = #{sku}")
    Product findBySku(@Param("sku") String sku);

    /**
     * 按分类查上架商品（阶段 10 推荐兜底：同分类热门/最新）。
     * 用于协同过滤候选不足时按用户关注的分类补位。
     */
    @Select("""
            SELECT * FROM products
            WHERE category_id = #{categoryId} AND status = 1
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<Product> selectByCategory(@Param("categoryId") Long categoryId, @Param("limit") int limit);

    /**
     * 无规格商品列表（管理端库存列表用）：
     * 没有 product_skus 的商品，其库存以商品级 stock/locked_stock 管理。
     * 可卖库存 = stock - locked_stock 在 SQL 中计算。
     */
    @Select("""
            SELECT p.*, (p.stock - p.locked_stock) AS available
            FROM products p
            WHERE NOT EXISTS (SELECT 1 FROM product_skus s WHERE s.product_id = p.id)
            ORDER BY p.id
            """)
    List<Product> selectNoSkuProducts();

    /**
     * 新增商品。
     *
     * @return 影响行数；自增主键回填到 product.id
     */
    @Insert("""
            INSERT INTO products (sku, name, description, price, stock, category_id, status)
            VALUES (#{sku}, #{name}, #{description}, #{price}, #{stock}, #{categoryId}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Product product);

    /** 更新商品基本信息 */
    @Update("""
            UPDATE products
            SET sku = #{sku},
                name = #{name},
                description = #{description},
                price = #{price},
                stock = #{stock},
                category_id = #{categoryId},
                status = #{status}
            WHERE id = #{id}
            """)
    int update(Product product);

    /** 更新上下架状态 */
    @Update("UPDATE products SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /** 删除商品 */
    @Delete("DELETE FROM products WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /**
     * 锁库存（下单未支付预占，阶段 6，商品级/无规格）。
     * 条件 UPDATE：可卖库存（stock - locked_stock）>= quantity 才锁定。
     */
    @Update("""
            UPDATE products
            SET locked_stock = locked_stock + #{quantity}
            WHERE id = #{id} AND stock - locked_stock >= #{quantity}
            """)
    int lockStock(@Param("id") Long id, @Param("quantity") int quantity);

    /**
     * 支付扣减（商品级，阶段 6）：把已锁定的库存正式卖出。
     */
    @Update("""
            UPDATE products
            SET stock = stock - #{quantity}, locked_stock = locked_stock - #{quantity}
            WHERE id = #{id} AND locked_stock >= #{quantity}
            """)
    int deductLocked(@Param("id") Long id, @Param("quantity") int quantity);

    /**
     * 释放锁定（商品级，阶段 6）：取消/关闭/超时未支付。
     */
    @Update("""
            UPDATE products
            SET locked_stock = locked_stock - #{quantity}
            WHERE id = #{id} AND locked_stock >= #{quantity}
            """)
    int releaseLocked(@Param("id") Long id, @Param("quantity") int quantity);

    /**
     * 原子调整库存（商品级，管理端人工调整）。
     * delta 可为正或负；条件 UPDATE 保证减后不为负。
     */
    @Update("""
            UPDATE products
            SET stock = stock + #{delta}
            WHERE id = #{id} AND stock + #{delta} >= 0
            """)
    int updateStock(@Param("id") Long id, @Param("delta") int delta);

    /** 恢复库存（订单取消时调用，把下单时扣掉的库存加回来） */
    @Update("UPDATE products SET stock = stock + #{quantity} WHERE id = #{id}")
    int restoreStock(@Param("id") Long id, @Param("quantity") int quantity);

    /**
     * 按 ID 批量查询商品（收藏夹列表用 / 阶段 12 ES 搜索命中后回查详情）。
     * 保持传入顺序（ORDER BY FIELD），即调用方传入的顺序。
     * LEFT JOIN categories 带出分类名（填充 Product.categoryName）。
     * 调用方必须保证 ids 非空。
     */
    @Select("""
            <script>
            SELECT p.*, c.name AS category_name
            FROM products p
            LEFT JOIN categories c ON p.category_id = c.id
            WHERE p.id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY FIELD(p.id,
            <foreach collection="ids" item="id" separator=",">#{id}</foreach>)
            </script>
            """)
    List<Product> selectByIds(@Param("ids") List<Long> ids);
}
