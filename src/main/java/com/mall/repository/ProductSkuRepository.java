package com.mall.repository;

import com.mall.entity.ProductSku;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商品规格数据访问层。
 */
@Mapper
public interface ProductSkuRepository {

    /** 按商品查询规格列表 */
    @Select("SELECT * FROM product_skus WHERE product_id = #{productId} ORDER BY id")
    List<ProductSku> selectByProductId(@Param("productId") Long productId);

    /** 按主键查询规格（购物车添加时校验规格存在且属于对应商品） */
    @Select("SELECT * FROM product_skus WHERE id = #{id}")
    ProductSku findById(@Param("id") Long id);

    /**
     * 管理端库存列表：全部规格 JOIN 商品带出展示字段。
     * 可卖库存 = stock - locked_stock 在 SQL 中计算（避免内存再算）。
     */
    @Select("""
            SELECT s.*,
                   p.name AS product_name,
                   p.sku   AS product_sku,
                   p.status AS product_status,
                   (s.stock - s.locked_stock) AS available
            FROM product_skus s
            JOIN products p ON s.product_id = p.id
            ORDER BY s.product_id, s.id
            """)
    List<ProductSku> selectAllWithProduct();

    /** 批量插入规格 */
    @Insert("""
            <script>
            INSERT INTO product_skus (product_id, name, price, stock, image) VALUES
            <foreach collection="list" item="sku" separator=",">
              (#{sku.productId}, #{sku.name}, #{sku.price}, #{sku.stock}, #{sku.image})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<ProductSku> skus);

    /** 删除某商品的全部规格（编辑/删除商品时先清后插） */
    @Delete("DELETE FROM product_skus WHERE product_id = #{productId}")
    int deleteByProductId(@Param("productId") Long productId);

    /**
     * 锁库存（下单未支付预占，阶段 6）。
     * 条件 UPDATE：可卖库存（stock - locked_stock）>= quantity 才锁定，数据库原子保证不超卖。
     */
    @Update("""
            UPDATE product_skus
            SET locked_stock = locked_stock + #{quantity}
            WHERE id = #{id} AND stock - locked_stock >= #{quantity}
            """)
    int lockStock(@Param("id") Long id, @Param("quantity") int quantity);

    /**
     * 支付扣减（把已锁定的库存正式卖出，阶段 6）。
     * 条件 UPDATE：locked_stock >= quantity 才扣，防止重复扣减。
     */
    @Update("""
            UPDATE product_skus
            SET stock = stock - #{quantity}, locked_stock = locked_stock - #{quantity}
            WHERE id = #{id} AND locked_stock >= #{quantity}
            """)
    int deductLocked(@Param("id") Long id, @Param("quantity") int quantity);

    /**
     * 释放锁定（取消/关闭/超时未支付，阶段 6）。
     * 条件 UPDATE：locked_stock >= quantity 才释放，防止释放过头（并发幂等）。
     */
    @Update("""
            UPDATE product_skus
            SET locked_stock = locked_stock - #{quantity}
            WHERE id = #{id} AND locked_stock >= #{quantity}
            """)
    int releaseLocked(@Param("id") Long id, @Param("quantity") int quantity);

    /**
     * 原子调整库存（管理端人工调整，阶段 6）。
     * delta 可为正（补货）或负（盘点减库存）；条件 UPDATE 保证减后不为负。
     */
    @Update("""
            UPDATE product_skus
            SET stock = stock + #{delta}
            WHERE id = #{id} AND stock + #{delta} >= 0
            """)
    int updateStock(@Param("id") Long id, @Param("delta") int delta);

    /** 恢复规格库存（订单取消时调用） */
    @Update("UPDATE product_skus SET stock = stock + #{quantity} WHERE id = #{id}")
    int restoreStock(@Param("id") Long id, @Param("quantity") int quantity);
}
