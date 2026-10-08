package com.mall.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券模板实体，对应 coupons 表（阶段 8）。
 *
 * <p>优惠券是"先领后用"的营销工具：管理员创建模板（满减券/折扣券），
 * 用户在领券中心领取后获得自己的 user_coupons 券，下单时可选用。
 *
 * <p>type 约定：1=满减券（threshold + amount），2=折扣券（threshold + discountRate）。
 */
@Data
public class Coupon {

    /** 模板主键 */
    private Long id;

    /** 优惠券名称（如"新人立减 20 元""全场 9 折券"） */
    private String name;

    /** 类型：1=满减券 2=折扣券 */
    private Integer type;

    /** 使用门槛：订单商品金额满 threshold 元可用；0=无门槛 */
    private BigDecimal threshold;

    /** 减免金额（满减券必填，如满 100 减 20 → amount=20） */
    private BigDecimal amount;

    /** 折扣率（折扣券必填，如 0.90=9 折；实付 = 金额 × discountRate） */
    private BigDecimal discountRate;

    /** 发行总量：0=不限量；领完即止 */
    private Integer totalQuantity;

    /** 每人限领张数（默认 1） */
    private Integer perUserLimit;

    /** 状态：1=上架（可领取） 0=下架（不可领取） */
    private Integer status;

    /** 有效期开始 */
    private LocalDateTime validFrom;

    /** 有效期结束 */
    private LocalDateTime validUntil;

    /** 创建时间 */
    private LocalDateTime createdAt;

    // ---------- 以下为非表字段（展示用） ----------

    /** 已领取数量（领券中心展示剩余量：total_quantity - received_count） */
    private Long receivedCount;

    /** 类型中文名（1=满减券 2=折扣券），Service 填充 */
    private String typeText;
}
