package com.mall.repository;

import com.mall.entity.InventoryLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 库存流水数据访问层。
 *
 * <p>职责：写流水（insert）、按订单/商品查流水；
 * 幂等释放判断（某订单是否已释放）也在这里完成。
 */
@Mapper
public interface InventoryLogRepository {

    /**
     * 插入一条库存流水（InventoryService 所有库存变更的统一出口）。
     * Phase 3：inventory_logs 按月分表，写操作必须显式指定分表名
     * （${tableName}，来自 ShardingRouter.routedTableName，内部常量拼接无注入风险）。
     * 注意：方法有两个参数，流水字段占位符需带 {@code log.} 前缀。
     */
    @Insert("""
            INSERT INTO ${tableName}
              (sku_id, product_id, change_qty, type, order_id, order_no, remark, operator_id)
            VALUES
              (#{log.skuId}, #{log.productId}, #{log.changeQty}, #{log.type}, #{log.orderId}, #{log.orderNo}, #{log.remark}, #{log.operatorId})
            """)
    int insert(@Param("tableName") String tableName, @Param("log") InventoryLog log);

    /** 按规格查流水（管理端库存流水查询；skuId=0 查商品级流水） */
    @Select("""
            <script>
            SELECT l.*,
                   p.name AS product_name,
                   s.name AS sku_name
            FROM inventory_logs l
            JOIN products p ON l.product_id = p.id
            LEFT JOIN product_skus s ON l.sku_id = s.id AND l.sku_id > 0
            WHERE l.sku_id = #{skuId}
            ORDER BY l.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<InventoryLog> selectBySkuId(@Param("skuId") Long skuId,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);

    /** 按商品查流水（商品级库存调整后查看） */
    @Select("""
            <script>
            SELECT l.*,
                   p.name AS product_name,
                   s.name AS sku_name
            FROM inventory_logs l
            JOIN products p ON l.product_id = p.id
            LEFT JOIN product_skus s ON l.sku_id = s.id AND l.sku_id > 0
            WHERE l.product_id = #{productId}
            ORDER BY l.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<InventoryLog> selectByProductId(@Param("productId") Long productId,
                                         @Param("offset") int offset,
                                         @Param("limit") int limit);

    /** 按规格统计流水条数（分页总数） */
    @Select("SELECT COUNT(*) FROM inventory_logs WHERE sku_id = #{skuId}")
    long countBySkuId(@Param("skuId") Long skuId);

    /** 按商品统计流水条数（商品级流水分页总数） */
    @Select("SELECT COUNT(*) FROM inventory_logs WHERE product_id = #{productId}")
    long countByProductId(@Param("productId") Long productId);

    /** 按订单查流水（取消/超时释放的幂等判断） */
    @Select("SELECT * FROM inventory_logs WHERE order_id = #{orderId} ORDER BY id")
    List<InventoryLog> selectByOrderId(@Param("orderId") Long orderId);

    /** 判断某订单是否已释放过锁定（存在 RELEASE 流水即已释放，防止重复释放） */
    @Select("SELECT COUNT(*) FROM inventory_logs WHERE order_id = #{orderId} AND type = 'RELEASE'")
    long countReleaseByOrderId(@Param("orderId") Long orderId);
}
