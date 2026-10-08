package com.mall.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付流水实体，对应 payments 表。
 *
 * <p>状态约定：0=待支付 1=已支付 2=已退款 3=支付失败。
 * 一条订单可有多条流水（重复发起支付会复用待支付流水），但支付成功后
 * 流水与订单状态联动（流水已支付 → 订单 0 待付款 → 1 待发货）。
 */
@Data
public class Payment {

    /** 支付流水主键 */
    private Long id;

    /** 支付流水号（唯一，PAY + 订单号生成规则） */
    private String paymentNo;

    /** 订单 ID */
    private Long orderId;

    /** 下单用户 ID */
    private Long userId;

    /** 支付方式：alipay=支付宝 / wechat=微信 */
    private String payMethod;

    /** 支付金额（= 订单总额，创建流水时锁定） */
    private BigDecimal amount;

    /** 状态：0待支付 1已支付 2已退款 3支付失败 */
    private Integer status;

    /** 第三方交易号（支付宝/微信支付平台流水号） */
    private String transactionId;

    /** 支付时间 */
    private LocalDateTime paidAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    // ---------- 以下为非表字段（JOIN 填充） ----------

    /** 订单号（管理端流水列表 LEFT JOIN orders 填充，便于对账） */
    private String orderNo;
}
