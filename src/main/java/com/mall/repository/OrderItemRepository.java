package com.mall.repository;

import com.mall.entity.OrderItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 订单明细数据访问层。
 */
@Mapper
public interface OrderItemRepository {

    /**
     * 批量插入订单明细（下单时一次写入全部快照）。
     * Phase 3：order_items 按 user_id % 4 分表，写操作必须显式指定分表名
     * （${tableName}，来自 ShardingRouter.routedTableName，内部常量拼接无注入风险）。
     */
    @Insert("""
            <script>
            INSERT INTO ${tableName} (order_id, product_id, product_name, product_image,
                                     sku_id, sku_name, price, quantity)
            VALUES
            <foreach collection="list" item="item" separator=",">
              (#{item.orderId}, #{item.productId}, #{item.productName}, #{item.productImage},
               #{item.skuId}, #{item.skuName}, #{item.price}, #{item.quantity})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("tableName") String tableName, @Param("list") List<OrderItem> items);

    /** 按订单查明细（详情页用） */
    @Select("SELECT * FROM order_items WHERE order_id = #{orderId} ORDER BY id")
    List<OrderItem> selectByOrderId(@Param("orderId") Long orderId);

    /** 按明细主键 + 所属订单查（评价时校验明细归属与是否存在） */
    @Select("SELECT * FROM order_items WHERE id = #{id} AND order_id = #{orderId}")
    OrderItem selectByIdAndOrderId(@Param("id") Long id, @Param("orderId") Long orderId);

    /**
     * 按多个订单批量查明细（订单列表用）。
     * 一次 IN 查询替代"逐订单查一次"的 N+1 问题。
     */
    @Select("""
            <script>
            SELECT * FROM order_items
            WHERE order_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
              #{id}
            </foreach>
            ORDER BY id
            </script>
            """)
    List<OrderItem> selectByOrderIds(@Param("ids") List<Long> ids);
}
