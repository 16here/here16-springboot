package com.mall.repository;

import com.mall.dto.OrderQueryDTO;
import com.mall.entity.Order;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 订单数据访问层。
 *
 * <p>状态约定：0=待付款 1=待发货 2=待收货 3=已完成 4=已取消。
 * 用户侧所有按 id 的操作都带 userId 条件防越权；
 * 状态流转使用"状态前置条件 + 条件 UPDATE"（如取消仅允许 0/1 状态），
 * 由数据库保证状态机合法。
 */
@Mapper
public interface OrderRepository {

    /** 新增订单（order_no 唯一；主键回填） */
    @Insert("""
            INSERT INTO orders (order_no, user_id, total_amount, original_amount,
                                promotion_amount, coupon_amount, coupon_id, status,
                                receiver_name, receiver_phone, receiver_address, remark)
            VALUES (#{orderNo}, #{userId}, #{totalAmount}, #{originalAmount},
                    #{promotionAmount}, #{couponAmount}, #{couponId}, #{status},
                    #{receiverName}, #{receiverPhone}, #{receiverAddress}, #{remark})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Order order);

    /** 用户订单分页（status 为 null 表示全部） */
    @Select("""
            <script>
            SELECT * FROM orders
            WHERE user_id = #{userId}
            <if test="status != null">
              AND status = #{status}
            </if>
            ORDER BY id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Order> selectPageByUser(@Param("userId") Long userId,
                                 @Param("status") Integer status,
                                 @Param("offset") int offset,
                                 @Param("pageSize") int pageSize);

    /** 用户订单总数（与分页条件一致） */
    @Select("""
            <script>
            SELECT COUNT(*) FROM orders
            WHERE user_id = #{userId}
            <if test="status != null">
              AND status = #{status}
            </if>
            </script>
            """)
    long countByUser(@Param("userId") Long userId, @Param("status") Integer status);

    /** 按主键 + 用户查（用户详情，归属校验） */
    @Select("SELECT * FROM orders WHERE id = #{id} AND user_id = #{userId}")
    Order selectByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 按主键查（管理端用，LEFT JOIN 带出下单人用户名） */
    @Select("""
            SELECT o.*, u.username
            FROM orders o
            LEFT JOIN users u ON o.user_id = u.id
            WHERE o.id = #{id}
            """)
    Order selectById(@Param("id") Long id);

    /** 管理端订单分页（状态筛选 + 订单号模糊搜索；LEFT JOIN 带用户名） */
    @Select("""
            <script>
            SELECT o.*, u.username
            FROM orders o
            LEFT JOIN users u ON o.user_id = u.id
            <where>
              <if test="dto.status != null">
                AND o.status = #{dto.status}
              </if>
              <if test="dto.keyword != null and dto.keyword != ''">
                AND o.order_no LIKE CONCAT('%', #{dto.keyword}, '%')
              </if>
            </where>
            ORDER BY o.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Order> adminPage(@Param("dto") OrderQueryDTO dto,
                          @Param("offset") int offset,
                          @Param("pageSize") int pageSize);

    /** 管理端订单总数 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM orders o
            <where>
              <if test="dto.status != null">
                AND o.status = #{dto.status}
              </if>
              <if test="dto.keyword != null and dto.keyword != ''">
                AND o.order_no LIKE CONCAT('%', #{dto.keyword}, '%')
              </if>
            </where>
            </script>
            """)
    long adminCount(@Param("dto") OrderQueryDTO dto);

    /**
     * 用户取消订单：仅允许 0（待付款）/1（待发货）状态取消。
     * 条件 UPDATE：状态前置 + userId 归属，rows=0 表示不可取消或不存在。
     */
    @Update("""
            UPDATE orders
            SET status = 4, cancelled_at = NOW()
            WHERE id = #{id} AND user_id = #{userId} AND status IN (0, 1)
            """)
    int cancelByUser(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 管理端发货：仅允许 1（待发货）状态发货，写发货时间与物流单号。
     * （阶段 5：发货事件由状态机校验后再调用，SQL 状态前置条件兜底并发）
     */
    @Update("""
            UPDATE orders
            SET status = 2, shipped_at = NOW(), shipping_no = #{shippingNo}
            WHERE id = #{id} AND status = 1
            """)
    int shipWithNo(@Param("id") Long id, @Param("shippingNo") String shippingNo);

    /**
     * 支付成功标记订单已付款（阶段 4 新增，阶段 5 接入状态机 PAY 事件）。
     * 条件 UPDATE：仅待付款（0）可转待发货（1）；rows=0 说明订单已非待付款
     * （如已被取消），支付回调不报错（真实场景应由支付平台发起退款，此处注释说明）。
     */
    @Update("UPDATE orders SET status = 1, paid_at = NOW() WHERE id = #{id} AND status = 0")
    int markPaid(@Param("id") Long id);

    /**
     * 买家确认收货：仅允许 2（待收货）状态确认 → 3（已完成），写完成时间。
     * （阶段 5 新增，CONFIRM 事件）
     */
    @Update("UPDATE orders SET status = 3, completed_at = NOW() WHERE id = #{id} AND status = 2")
    int confirm(@Param("id") Long id);

    /**
     * 取消"待付款"订单：0（待付款）→ 4（已取消），写取消时间。
     * 供两处使用：超时任务（TIMEOUT 事件）、管理端关闭（CLOSE 事件）。
     * （阶段 5 新增；用户取消走 cancelByUser，可取消范围含待发货）
     */
    @Update("UPDATE orders SET status = 4, cancelled_at = NOW() WHERE id = #{id} AND status = 0")
    int cancelPending(@Param("id") Long id);

    /**
     * 查询超时未支付订单（定时任务用）：
     * 待付款且创建时间早于（当前时间 - timeoutMinutes 分钟），按时间升序取一批。
     * 索引 idx_status_created(status, created_at) 支撑该查询。
     */
    @Select("""
            SELECT * FROM orders
            WHERE status = 0
              AND created_at < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE)
            ORDER BY id
            LIMIT #{limit}
            """)
    List<Order> selectTimeoutOrders(@Param("minutes") int minutes, @Param("limit") int limit);

    /**
     * 售后退款关闭订单（阶段 7）：仅退款场景（订单已付款未发货，状态 1）退款成功后
     * 关闭订单 → 已取消（4）。库存回补由 AfterSaleService 统一执行（restoreSold），
     * 本方法只负责状态落库（条件 UPDATE + 状态前置保证只关一次）。
     */
    @Update("UPDATE orders SET status = 4, cancelled_at = NOW() WHERE id = #{id} AND status = 1")
    int refundClose(@Param("id") Long id);

    /** 删除订单明细（下单失败回滚时清理残留明细，事务内调用） */
    @Delete("DELETE FROM order_items WHERE order_id = #{orderId}")
    int deleteItemsByOrderId(@Param("orderId") Long orderId);
}
