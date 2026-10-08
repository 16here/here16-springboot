package com.mall.repository;

import com.mall.entity.UserCoupon;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户优惠券数据访问层（阶段 8）。
 *
 * <p>生命周期状态：0=未使用 1=已锁定(下单) 2=已使用(核销) 3=已过期。
 * 锁定/核销/释放全部用"条件 UPDATE + 状态前置"，保证一张券同一时刻只被一个订单使用。
 */
@Mapper
public interface UserCouponRepository {

    /** 领取：新增一张用户券（主键回填） */
    @Insert("""
            INSERT INTO user_coupons (coupon_id, user_id, status, expire_at)
            VALUES (#{couponId}, #{userId}, 0, #{expireAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserCoupon userCoupon);

    /** 按主键 + 用户查（归属校验） */
    @Select("SELECT * FROM user_coupons WHERE id = #{id} AND user_id = #{userId}")
    UserCoupon selectByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 按主键查（下单锁券校验用，带模板信息） */
    @Select("""
            SELECT uc.*, c.name AS coupon_name, c.type AS coupon_type,
                   c.threshold, c.amount, c.discount_rate
            FROM user_coupons uc
            JOIN coupons c ON uc.coupon_id = c.id
            WHERE uc.id = #{id}
            """)
    UserCoupon selectById(@Param("id") Long id);

    /**
     * 我的优惠券分页（用户端）。
     * statusTab：0=未使用（含已锁定 status IN (0,1)，尚未核销都算可用态）、
     * 2=已使用、3=已过期；null=全部。
     */
    @Select("""
            <script>
            SELECT uc.*, c.name AS coupon_name, c.type AS coupon_type,
                   c.threshold, c.amount, c.discount_rate
            FROM user_coupons uc
            JOIN coupons c ON uc.coupon_id = c.id
            WHERE uc.user_id = #{userId}
            <choose>
              <when test="statusTab == 0">
                AND uc.status IN (0, 1)
              </when>
              <when test="statusTab != null">
                AND uc.status = #{statusTab}
              </when>
            </choose>
            ORDER BY uc.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<UserCoupon> selectByUser(@Param("userId") Long userId,
                                  @Param("statusTab") Integer statusTab,
                                  @Param("offset") int offset,
                                  @Param("pageSize") int pageSize);

    /** 我的优惠券总数（与分页条件一致） */
    @Select("""
            <script>
            SELECT COUNT(*) FROM user_coupons
            WHERE user_id = #{userId}
            <choose>
              <when test="statusTab == 0">
                AND status IN (0, 1)
              </when>
              <when test="statusTab != null">
                AND status = #{statusTab}
              </when>
            </choose>
            </script>
            """)
    long countByUser(@Param("userId") Long userId, @Param("statusTab") Integer statusTab);

    /** 某用户领取某模板的数量（每人限领校验） */
    @Select("SELECT COUNT(*) FROM user_coupons WHERE coupon_id = #{couponId} AND user_id = #{userId}")
    long countReceived(@Param("couponId") Long couponId, @Param("userId") Long userId);

    /**
     * 可用券列表：订单结算页展示"哪些券能用"。
     * 条件：券未使用(0)、模板上架、未过期、门槛 ≤ 订单商品金额。
     */
    @Select("""
            SELECT uc.*, c.name AS coupon_name, c.type AS coupon_type,
                   c.threshold, c.amount, c.discount_rate
            FROM user_coupons uc
            JOIN coupons c ON uc.coupon_id = c.id
            WHERE uc.user_id = #{userId}
              AND uc.status = 0
              AND c.status = 1
              AND uc.expire_at > #{now}
              AND c.threshold <= #{amount}
            ORDER BY c.threshold DESC, uc.id ASC
            """)
    List<UserCoupon> selectAvailable(@Param("userId") Long userId,
                                     @Param("amount") java.math.BigDecimal amount,
                                     @Param("now") LocalDateTime now);

    /**
     * 下单锁定：仅未使用(0)可锁 → 已锁定(1)并绑定订单。
     * 条件 UPDATE：rows=0 说明券不存在/已使用/已过期，锁券失败。
     */
    @Update("""
            UPDATE user_coupons
            SET status = 1, order_id = #{orderId}
            WHERE id = #{id} AND user_id = #{userId} AND status = 0
            """)
    int lockCoupon(@Param("id") Long id, @Param("userId") Long userId, @Param("orderId") Long orderId);

    /**
     * 支付成功核销：仅已锁定(1)可核销 → 已使用(2) + 核销时间。
     * 幂等：重复回调/重复调用 rows=0 不影响已核销状态。
     */
    @Update("""
            UPDATE user_coupons
            SET status = 2, used_at = NOW()
            WHERE order_id = #{orderId} AND status = 1
            """)
    int markUsedByOrder(@Param("orderId") Long orderId);

    /**
     * 订单取消/关闭/超时释放：仅已锁定(1)且绑定该订单的券释放回未使用(0)，
     * 清除订单绑定。幂等：已核销(2)的券不会被释放，未锁定的券不受影响。
     */
    @Update("""
            UPDATE user_coupons
            SET status = 0, order_id = NULL
            WHERE order_id = #{orderId} AND status = 1
            """)
    int releaseByOrder(@Param("orderId") Long orderId);
}
