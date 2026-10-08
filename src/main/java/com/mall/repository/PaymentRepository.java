package com.mall.repository;

import com.mall.entity.Payment;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 支付流水数据访问层。
 *
 * <p>关键设计：
 * <ul>
 *   <li>payment_no 唯一：同一条订单重复发起支付会复用"待支付"流水，不会重复创建；</li>
 *   <li>updateToPaid 用"条件 UPDATE（status=0）"保证回调幂等——重复回调时第二次
 *       影响行数为 0，不会重复更新；</li>
 *   <li>管理端分页 LEFT JOIN orders 带出订单号，便于按订单号对账。</li>
 * </ul>
 */
@Mapper
public interface PaymentRepository {

    /** 新增支付流水（payment_no 唯一；主键回填） */
    @Insert("""
            INSERT INTO payments (payment_no, order_id, user_id, pay_method, amount, status)
            VALUES (#{paymentNo}, #{orderId}, #{userId}, #{payMethod}, #{amount}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Payment payment);

    /** 按支付流水号查（回调/查状态用） */
    @Select("SELECT * FROM payments WHERE payment_no = #{paymentNo}")
    Payment findByPaymentNo(@Param("paymentNo") String paymentNo);

    /** 按订单查指定状态的流水（创建支付时复用待支付流水） */
    @Select("SELECT * FROM payments WHERE order_id = #{orderId} AND status = #{status} ORDER BY id DESC LIMIT 1")
    Payment selectByOrderIdAndStatus(@Param("orderId") Long orderId, @Param("status") Integer status);

    /**
     * 标记支付成功（回调处理）。
     * 条件 UPDATE：仅待支付（0）可转已支付（1），rows=0 说明已处理过（幂等）。
     */
    @Update("""
            UPDATE payments
            SET status = 1, transaction_id = #{transactionId}, paid_at = NOW()
            WHERE id = #{id} AND status = 0
            """)
    int updateToPaid(@Param("id") Long id, @Param("transactionId") String transactionId);

    /**
     * 售后退款标记支付流水已退款（阶段 7）。
     * 条件 UPDATE：仅已支付（1）可转已退款（2），重复退款/从未支付不受影响（幂等）。
     * 关联订单的全部已支付流水一起退款（一单可能有多次支付流水，对账一致）。
     */
    @Update("UPDATE payments SET status = 2 WHERE order_id = #{orderId} AND status = 1")
    int markRefundedByOrder(@Param("orderId") Long orderId);

    /** 管理端流水分页（支付方式/状态筛选 + 订单号模糊搜索；LEFT JOIN 带订单号） */
    @Select("""
            <script>
            SELECT p.*, o.order_no
            FROM payments p
            LEFT JOIN orders o ON p.order_id = o.id
            <where>
              <if test="payMethod != null and payMethod != ''">
                AND p.pay_method = #{payMethod}
              </if>
              <if test="status != null">
                AND p.status = #{status}
              </if>
              <if test="keyword != null and keyword != ''">
                AND (o.order_no LIKE CONCAT('%', #{keyword}, '%')
                     OR p.payment_no LIKE CONCAT('%', #{keyword}, '%'))
              </if>
            </where>
            ORDER BY p.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Payment> adminPage(@Param("payMethod") String payMethod,
                            @Param("status") Integer status,
                            @Param("keyword") String keyword,
                            @Param("offset") int offset,
                            @Param("pageSize") int pageSize);

    /** 管理端流水总数（与分页条件一致） */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM payments p
            LEFT JOIN orders o ON p.order_id = o.id
            <where>
              <if test="payMethod != null and payMethod != ''">
                AND p.pay_method = #{payMethod}
              </if>
              <if test="status != null">
                AND p.status = #{status}
              </if>
              <if test="keyword != null and keyword != ''">
                AND (o.order_no LIKE CONCAT('%', #{keyword}, '%')
                     OR p.payment_no LIKE CONCAT('%', #{keyword}, '%'))
              </if>
            </where>
            </script>
            """)
    long adminCount(@Param("payMethod") String payMethod,
                    @Param("status") Integer status,
                    @Param("keyword") String keyword);
}
