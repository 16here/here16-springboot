package com.mall.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 售后单实体，对应 after_sales 表（阶段 7）。
 *
 * <p>售后类型与状态：
 * <ul>
 *   <li>type：1=仅退款（未发货，钱退货不退） 2=退货退款（已发货/已收货，货与钱都退）</li>
 *   <li>status：0=待审核 →（审核通过）→ 1=待寄回（仅退货退款）→ 2=已完成（已退款）；
 *       0 →（拒绝）→ 3=已拒绝；0 →（用户撤销）→ 4=已撤销</li>
 *   <li>退款完成时联动：回补库存（已扣的加回可卖）＋支付流水置已退款＋
 *       仅退款且未发货时关闭订单（1→4）</li>
 * </ul>
 */
@Data
public class AfterSale {

    /** 售后单主键 */
    private Long id;

    /** 售后单号（唯一，AS+时间戳） */
    private String afterSaleNo;

    /** 订单 ID */
    private Long orderId;

    /** 订单号（冗余，便于检索） */
    private String orderNo;

    /** 申请用户 ID */
    private Long userId;

    /** 类型：1=仅退款 2=退货退款 */
    private Integer type;

    /** 申请原因 */
    private String reason;

    /** 退款金额（≤ 订单实付金额） */
    private BigDecimal amount;

    /** 状态：0=待审核 1=待寄回 2=已完成 3=已拒绝 4=已撤销 */
    private Integer status;

    /** 退货物流单号（退货退款场景） */
    private String logisticsNo;

    /** 审核意见（拒绝时必填） */
    private String auditRemark;

    /** 申请时间 */
    private LocalDateTime applyAt;

    /** 审核时间 */
    private LocalDateTime auditAt;

    /** 退款完成时间 */
    private LocalDateTime refundAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    // ---------- 非表字段（JOIN/展示用，不落库） ----------

    /** 用户名（JOIN users） */
    private String username;

    /** 订单状态（快照展示用，JOIN orders） */
    private Integer orderStatus;

    /** 订单总金额（JOIN orders） */
    private BigDecimal orderTotalAmount;
}
