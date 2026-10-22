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

    /**
     * 新增订单（order_no 唯一；主键为雪花 ID，由 OrderService 显式赋值）。
     *
     * <p>Phase 3 分表：orders 按 user_id % 4 分表，写操作必须显式指定
     * 分表名（{@code ${tableName}}，来自 ShardingRouter.routedTableName），
     * 表名是内部常量拼接、非用户输入，无 SQL 注入风险。
     * <p>注意：
     * <ul>
     *   <li>方法有两个参数（tableName + order），MyBatis 会把它们包装成 Map，
     *       所以 Order 字段占位符必须带 {@code order.} 前缀；</li>
     *   <li><b>必须显式插入 id 列（雪花 ID）</b>：分表后 4 张表各自自增会撞主键，
     *       主键由应用生成；若省略 id 列让 MySQL 自增，内存里的雪花 ID 与落库
     *       主键不一致，后续按 order.getId() 查询必然 404。</li>
     * </ul>
     */
    @Insert("""
            INSERT INTO ${tableName} (id, order_no, user_id, total_amount, original_amount,
                                promotion_amount, coupon_amount, coupon_id, status,
                                receiver_name, receiver_phone, receiver_address, remark)
            VALUES (#{order.id}, #{order.orderNo}, #{order.userId}, #{order.totalAmount}, #{order.originalAmount},
                    #{order.promotionAmount}, #{order.couponAmount}, #{order.couponId}, #{order.status},
                    #{order.receiverName}, #{order.receiverPhone}, #{order.receiverAddress}, #{order.remark})
            """)
    int insert(@Param("tableName") String tableName, @Param("order") Order order);

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
    /** 按订单 ID 查（用户端查物流用，带 userId 防越权） */
    @Select("SELECT * FROM orders WHERE id = #{id} AND user_id = #{userId}")
    Order selectByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 按订单号 + 用户查（AI 客服"用户发具体订单号"场景）。
     *
     * <p>用户聊天里发的 19 位长数字是订单号 order_no（如 2026093014190000014675），
     * 不是数据库主键 id；工具必须按 order_no 精确查，并带 userId 做归属校验，
     * 防止 AI 服务用他人订单号越权读取。
     */
    @Select("SELECT * FROM orders WHERE order_no = #{orderNo} AND user_id = #{userId}")
    Order selectByOrderNoAndUserId(@Param("orderNo") String orderNo, @Param("userId") Long userId);

    /** 按主键查（管理端用，LEFT JOIN 带出下单人用户名） */
    @Select("""
            SELECT o.*, u.username
            FROM orders o
            LEFT JOIN users u ON o.user_id = u.id
            WHERE o.id = #{id}
            """)
    Order selectById(@Param("id") Long id);

    /** 按 ID 批量查订单（管理端运单列表补收货人信息用；无 userId，
     *  由调用方显式 routeOrdersBroadcast 跨分片查询） */
    @Select("""
            <script>
            SELECT * FROM orders WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            </script>
            """)
    List<Order> selectByIds(@Param("ids") List<Long> ids);

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
     * Phase 3：tableName 显式指定分表。
     */
    @Update("""
            UPDATE ${tableName}
            SET status = 4, cancelled_at = NOW()
            WHERE id = #{id} AND user_id = #{userId} AND status IN (0, 1)
            """)
    int cancelByUser(@Param("tableName") String tableName,
                     @Param("id") Long id, @Param("userId") Long userId);

    /**
     * 管理端发货：仅允许 1（待发货）状态发货，写发货时间与物流单号。
     * （阶段 5：发货事件由状态机校验后再调用，SQL 状态前置条件兜底并发）
     * Phase 3：tableName 显式指定分表。
     */
    @Update("""
            UPDATE ${tableName}
            SET status = 2, shipped_at = NOW(), shipping_no = #{shippingNo}
            WHERE id = #{id} AND status = 1
            """)
    int shipWithNo(@Param("tableName") String tableName,
                   @Param("id") Long id, @Param("shippingNo") String shippingNo);

    /**
     * 支付成功标记订单已付款（阶段 4 新增，阶段 5 接入状态机 PAY 事件）。
     * 条件 UPDATE：仅待付款（0）可转待发货（1）；rows=0 说明订单已非待付款
     * （如已被取消），支付回调不报错（真实场景应由支付平台发起退款，此处注释说明）。
     * Phase 3：tableName 显式指定分表。
     */
    @Update("UPDATE ${tableName} SET status = 1, paid_at = NOW() WHERE id = #{id} AND status = 0")
    int markPaid(@Param("tableName") String tableName, @Param("id") Long id);

    /**
     * 买家确认收货：仅允许 2（待收货）状态确认 → 3（已完成），写完成时间。
     * （阶段 5 新增，CONFIRM 事件）
     * Phase 3：tableName 显式指定分表。
     */
    @Update("UPDATE ${tableName} SET status = 3, completed_at = NOW() WHERE id = #{id} AND status = 2")
    int confirm(@Param("tableName") String tableName, @Param("id") Long id);

    /**
     * 取消"待付款"订单：0（待付款）→ 4（已取消），写取消时间。
     * 供两处使用：超时任务（TIMEOUT 事件）、管理端关闭（CLOSE 事件）。
     * （阶段 5 新增；用户取消走 cancelByUser，可取消范围含待发货）
     * Phase 3：tableName 显式指定分表。
     */
    @Update("UPDATE ${tableName} SET status = 4, cancelled_at = NOW() WHERE id = #{id} AND status = 0")
    int cancelPending(@Param("tableName") String tableName, @Param("id") Long id);

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
     * Phase 3：tableName 显式指定分表。
     */
    @Update("UPDATE ${tableName} SET status = 4, cancelled_at = NOW() WHERE id = #{id} AND status = 1")
    int refundClose(@Param("tableName") String tableName, @Param("id") Long id);

    /** 删除订单明细（下单失败回滚时清理残留明细，事务内调用）
     *  Phase 3：tableName 显式指定分表。 */
    @Delete("DELETE FROM ${tableName} WHERE order_id = #{orderId}")
    int deleteItemsByOrderId(@Param("tableName") String tableName, @Param("orderId") Long orderId);
}
