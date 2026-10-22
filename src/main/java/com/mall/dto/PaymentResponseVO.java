package com.mall.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付响应 VO：发起支付 / 查询状态共用。
 *
 * <p>关键字段：
 * <ul>
 *   <li>payUrl：真实模式为第三方支付链接（前端跳转或生成二维码）；
 *       mock 模式为模拟支付标识；</li>
 *   <li>mock：是否模拟支付（前端据此决定展示"模拟扫码支付"流程）；</li>
 *   <li>mockSign：模拟签名（仅 mock 模式返回），前端模拟支付时随回调参数携带；</li>
 *   <li>expireSeconds：支付凭证有效期，前端做倒计时。</li>
 * </ul>
 */
@Data
public class PaymentResponseVO {

    /** 支付流水号 */
    private String paymentNo;

    /**
     * 订单 ID（雪花 ID，19 位，输出为字符串）。
     * 类型用 String 防 JS Number 精度丢失导致支付页/回调查不到订单（见 OrderVO.id 说明）。
     */
    private String orderId;

    /** 订单号 */
    private String orderNo;

    /** 支付方式 */
    private String payMethod;

    /** 支付金额 */
    private BigDecimal amount;

    /** 支付凭证（真实支付链接 / mock 标识） */
    private String payUrl;

    /** 流水状态：0待支付 1已支付 */
    private Integer status;

    /** 状态中文名 */
    private String statusText;

    /** 支付凭证有效期（秒），前端倒计时 */
    private Integer expireSeconds;

    /** 是否模拟支付 */
    private Boolean mock;

    /** 模拟签名（仅 mock 模式返回，用于模拟回调验签） */
    private String mockSign;

    /** 支付时间 */
    private LocalDateTime paidAt;
}
