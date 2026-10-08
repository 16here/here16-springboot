package com.mall.dto;

import lombok.Data;

/**
 * 支付回调处理结果 VO（回调接口返回给支付平台 / mock 调用方）。
 *
 * <p>真实支付宝约定回调必须返回纯文本 "success"（否则会重复通知）；
 * 本 VO 供 mock 模式与接口文档使用，Controller 转成约定的文本返回。
 */
@Data
public class PaymentCallbackDTO {

    /** 回调处理是否成功 */
    private Boolean success;

    /** 处理说明（验签失败 / 流水不存在等） */
    private String message;

    /** 支付流水号 */
    private String paymentNo;

    /** 订单号（方便排查对账） */
    private String orderNo;
}
