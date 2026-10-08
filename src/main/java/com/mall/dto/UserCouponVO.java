package com.mall.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户优惠券展示 VO（我的优惠券 / 可用券列表共用）。
 */
@Data
public class UserCouponVO {

    /** 用户券主键 */
    private Long id;

    /** 所属模板 ID */
    private Long couponId;

    /** 券名称 */
    private String couponName;

    /** 类型：1=满减券 2=折扣券 */
    private Integer type;

    /** 类型中文名 */
    private String typeText;

    /** 使用门槛 */
    private BigDecimal threshold;

    /** 减免金额（满减券） */
    private BigDecimal amount;

    /** 折扣率（折扣券） */
    private BigDecimal discountRate;

    /** 状态：0=未使用 1=已锁定 2=已使用 3=已过期 */
    private Integer status;

    /** 状态中文名 */
    private String statusText;

    /** 锁定/核销关联订单 ID */
    private Long orderId;

    /** 领取时间 */
    private LocalDateTime receivedAt;

    /** 过期时间 */
    private LocalDateTime expireAt;

    /** 核销时间 */
    private LocalDateTime usedAt;
}
