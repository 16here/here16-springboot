package com.mall.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券模板展示 VO（领券中心 + 管理端列表共用）。
 */
@Data
public class CouponVO {

    /** 模板主键 */
    private Long id;

    /** 券名称 */
    private String name;

    /** 类型：1=满减券 2=折扣券 */
    private Integer type;

    /** 类型中文名 */
    private String typeText;

    /** 使用门槛（满 X 元可用，0=无门槛） */
    private BigDecimal threshold;

    /** 减免金额（满减券） */
    private BigDecimal amount;

    /** 折扣率（折扣券） */
    private BigDecimal discountRate;

    /** 发行总量（0=不限量） */
    private Integer totalQuantity;

    /** 每人限领 */
    private Integer perUserLimit;

    /** 状态：1=上架 0=下架 */
    private Integer status;

    /** 状态中文名 */
    private String statusText;

    /** 有效期开始 */
    private LocalDateTime validFrom;

    /** 有效期结束 */
    private LocalDateTime validUntil;

    /** 已领取数量（领券中心剩余量展示用） */
    private Long receivedCount;

    /** 当前登录用户是否已领取（领券中心按钮态：已领取置灰） */
    private Boolean received;
}
