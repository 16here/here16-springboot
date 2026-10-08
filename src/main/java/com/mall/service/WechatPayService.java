package com.mall.service;

import com.mall.config.PaymentConfig;
import com.mall.config.WechatPayConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 微信支付封装。
 *
 * <p>当前为 mock 模式（本地开发）：不依赖微信支付 SDK 与商户证书，
 * 直接返回模拟支付标识；接入真实微信支付（V3）时在 {@link #createPayUrl}
 * 中改用微信支付 API 生成 Native 下单二维码链接（code_url），
 * 在 {@link #verifyCallback} 中用平台证书验签 + 解密回调报文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatPayService implements PaymentGateway {

    private final WechatPayConfig wechatPayConfig;
    private final PaymentConfig paymentConfig;

    /** 支付方式标识：微信支付 */
    @Override
    public String method() {
        return "wechat";
    }

    /** 生成支付凭证（mock 模式返回模拟标识，真实模式返回微信 Native 二维码链接） */
    @Override
    public String createPayUrl(String paymentNo, BigDecimal amount) {
        if (paymentConfig.isMockEnabled()) {
            return "mock://wechat?paymentNo=" + paymentNo + "&amount=" + amount;
        }
        // ---------- 接入真实微信支付的接入点（注释留痕） ----------
        // 微信支付 V3 Native 下单：构造 pay/transactions/native 请求，
        // 携带 mchid、appid、out_trade_no、amount、notify_url，
        // 用商户私钥签名，成功后返回 code_url 供前端生成二维码。
        // 依赖：wechatpay-java SDK + 商户证书（merchantId/apiKey/mchSerialNo/privateKeyPath）
        throw new UnsupportedOperationException("未配置真实微信商户信息，请先开通商户号并填写 WechatPayConfig");
    }

    /**
     * 回调验签。
     * mock 模式：与支付宝相同的 mock 签名规则（演示验签概念）；
     * 真实模式：微信 V3 回调需验签名 + 解密 resource 字段。
     */
    @Override
    public boolean verifyCallback(Map<String, String> params) {
        if (paymentConfig.isMockEnabled()) {
            String paymentNo = params.get("paymentNo");
            String amount = params.get("amount");
            String sign = params.get("mockSign");
            if (paymentNo == null || amount == null || sign == null) {
                return false;
            }
            String expected = paymentConfig.mockSign(paymentNo, amount);
            boolean ok = expected.equals(sign);
            if (!ok) {
                log.warn("微信 mock 回调验签失败: paymentNo={}", paymentNo);
            }
            return ok;
        }
        // ---------- 真实微信验签接入点 ----------
        // 校验 Wechatpay-Signature / Wechatpay-Timestamp / Wechatpay-Nonce 头，
        // 用平台证书验签后用 API v3 密钥 AES-GCM 解密 resource 得到订单信息。
        throw new UnsupportedOperationException("未配置真实微信支付，无法验签");
    }
}
