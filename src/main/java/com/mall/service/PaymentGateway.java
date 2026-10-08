package com.mall.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 支付网关接口（策略模式）。
 *
 * <p>支付宝与微信支付各自实现本接口，通过 {@link PaymentGatewayFactory}
 * 按支付方式返回对应实现——业务层（PaymentService）不关心具体是哪家支付，
 * 只面向本接口编程，新增支付方式只需新增一个实现类。
 */
public interface PaymentGateway {

    /** 支付方式标识：alipay / wechat */
    String method();

    /**
     * 生成支付凭证（支付链接 / 二维码内容）。
     *
     * @param paymentNo 支付流水号
     * @param amount    支付金额
     * @return 支付凭证：真实模式为第三方支付链接，mock 模式为模拟支付标识
     */
    String createPayUrl(String paymentNo, BigDecimal amount);

    /**
     * 回调验签。
     *
     * <p>真实模式：支付宝用 AlipaySignature.rsaCheckV1、微信用 API v3 验签；
     * mock 模式：校验签名是否由后端 mockKey 计算得出（演示验签概念）。
     *
     * @param params 回调参数（表单字段）
     * @return 验签是否通过
     */
    boolean verifyCallback(Map<String, String> params);
}
