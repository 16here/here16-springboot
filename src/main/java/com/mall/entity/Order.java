package com.mall.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单主表实体，对应 orders 表。
 *
 * <p>状态约定：0=待付款 1=待发货 2=待收货 3=已完成 4=已取消。
 * username 为非表字段，管理端列表 LEFT JOIN users 填充，用于显示下单人。
 */
@Data
public class Order {

    /** 订单主键 */
    private Long id;

    /** 订单号（唯一，由 OrderNoGenerator 生成） */
    private String orderNo;

    /** 下单用户 ID */
    private Long userId;

    /** 订单总金额（应付金额 = 原价合计 - 活动优惠 - 优惠券；下单时计算） */
    private BigDecimal totalAmount;

    /** 商品原价合计（优惠前，阶段 8：金额明细展示用） */
    private BigDecimal originalAmount;

    /** 活动优惠金额（满减/折扣/秒杀，阶段 8） */
    private BigDecimal promotionAmount;

    /** 优惠券减免金额（阶段 8） */
    private BigDecimal couponAmount;

    /** 使用的用户优惠券 ID（user_coupons.id，阶段 8；未用券为 NULL） */
    private Long couponId;

    /** 订单状态：0待付款 1待发货 2待收货 3已完成 4已取消 */
    private Integer status;

    /** 收货人姓名（地址快照） */
    private String receiverName;

    /** 收货电话（地址快照） */
    private String receiverPhone;

    /** 完整收货地址（地址快照） */
    private String receiverAddress;

    /** 买家备注 */
    private String remark;

    /** 物流单号（阶段 5 起管理端发货时填写） */
    private String shippingNo;

    /** 下单时间 */
    private LocalDateTime createdAt;

    /** 付款时间 */
    private LocalDateTime paidAt;

    /** 发货时间 */
    private LocalDateTime shippedAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

    /** 取消时间 */
    private LocalDateTime cancelledAt;

    // ---------- 以下为非表字段（JOIN 填充） ----------

    /** 下单人用户名（管理端列表显示） */
    private String username;
}
