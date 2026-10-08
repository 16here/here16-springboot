package com.mall.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户优惠券实体，对应 user_coupons 表（阶段 8）。
 *
 * <p>用户领取优惠券模板后产生一张"用户券"，生命周期：
 * <pre>
 *   0 未使用 --(下单锁定)--> 1 已锁定 --(支付成功核销)--> 2 已使用
 *   1 已锁定 --(订单取消/关闭/超时)--> 0 未使用（释放，可再次使用）
 *   0 未使用 --(超过有效期)--> 3 已过期
 * </pre>
 *
 * <p>orderId 记录锁定/核销时的订单：取消释放时按订单号幂等判断。
 */
@Data
public class UserCoupon {

    /** 用户券主键 */
    private Long id;

    /** 所属优惠券模板 ID */
    private Long couponId;

    /** 领取用户 ID */
    private Long userId;

    /** 状态：0=未使用 1=已锁定(下单) 2=已使用(核销) 3=已过期 */
    private Integer status;

    /** 锁定/核销关联的订单 ID（未使用时为 NULL） */
    private Long orderId;

    /** 领取时间 */
    private LocalDateTime receivedAt;

    /** 过期时间（领取时快照模板有效期） */
    private LocalDateTime expireAt;

    /** 核销时间 */
    private LocalDateTime usedAt;

    // ---------- 以下为非表字段（JOIN coupons 带出，我的券列表展示） ----------

    /** 券名称 */
    private String couponName;

    /** 券类型（1=满减 2=折扣） */
    private Integer couponType;

    /** 使用门槛 */
    private BigDecimal threshold;

    /** 减免金额（满减券） */
    private BigDecimal amount;

    /** 折扣率（折扣券） */
    private BigDecimal discountRate;

    /** 状态中文名（Service 填充：未使用/已锁定/已使用/已过期） */
    private String statusText;
}
