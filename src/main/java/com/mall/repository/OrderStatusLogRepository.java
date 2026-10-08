package com.mall.repository;

import com.mall.entity.OrderStatusLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 订单状态变更日志数据访问层（order_status_log）。
 *
 * <p>只写不删：状态日志是审计数据，一经记录不允许修改/删除，
 * 以便追溯订单完整生命周期。
 */
@Mapper
public interface OrderStatusLogRepository {

    /** 新增一条状态变更日志（主键回填） */
    @Insert("""
            INSERT INTO order_status_log
                (order_id, order_no, from_status, to_status, event, operator_type, operator_id, remark)
            VALUES
                (#{orderId}, #{orderNo}, #{fromStatus}, #{toStatus}, #{event}, #{operatorType}, #{operatorId}, #{remark})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OrderStatusLog log);

    /** 按订单查全部状态日志（按时间正序 = 时间轴顺序） */
    @Select("SELECT * FROM order_status_log WHERE order_id = #{orderId} ORDER BY id ASC")
    List<OrderStatusLog> selectByOrderId(@Param("orderId") Long orderId);

    /** 按多个订单批量查日志（列表页一次 IN 查询，避免 N+1） */
    @Select("""
            <script>
            SELECT * FROM order_status_log
            WHERE order_id IN
            <foreach collection="orderIds" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            ORDER BY id ASC
            </script>
            """)
    List<OrderStatusLog> selectByOrderIds(@Param("orderIds") List<Long> orderIds);
}
