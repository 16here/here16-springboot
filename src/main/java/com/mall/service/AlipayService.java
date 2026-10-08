package com.mall.service;

import com.mall.config.AlipayConfig;
import com.mall.config.PaymentConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 支付宝支付封装。
 *
 * <p>当前为 mock 模式（本地开发）：不依赖支付宝 SDK 与商户密钥，
 * 直接返回模拟支付标识；接入真实支付宝时在 {@link #createPayUrl} 中
 * 改用 AlipayClient + AlipayTradePagePayRequest 生成支付表单/链接，
 * 在 {@link #verifyCallback} 中用 AlipaySignature.rsaCheckV1 验签即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlipayService implements PaymentGateway {

    private final AlipayConfig alipayConfig;
    private final PaymentConfig paymentConfig;

    /** 支付方式标识：支付宝 */
    @Override
    public String method() {
        return "alipay";
    }

    /** 生成支付凭证（mock 模式返回模拟标识，真实模式返回支付宝支付链接） */
    @Override
    public String createPayUrl(String paymentNo, BigDecimal amount) {
        if (paymentConfig.isMockEnabled()) {
            // 模拟模式：前端据此展示"模拟扫码"，并携带 mockSign 调用回调接口完成闭环
            return "mock://alipay?paymentNo=" + paymentNo + "&amount=" + amount;
        }
        // ---------- 接入真实支付宝的接入点（注释留痕，避免踩坑） ----------
        // AlipayClient client = new DefaultAlipayClient(gateway, appId, privateKey, "json", "UTF-8", alipayPublicKey, "RSA2");
        // AlipayTradePagePayRequest req = new AlipayTradePagePayRequest();
        // req.setNotifyUrl(paymentConfig.getCallbackUrl() + "/alipay");
        // req.setBizContent("{\"out_trade_no\":\"" + paymentNo + "\",\"total_amount\":\"" + amount + "\",...}");
        // return client.pageExecute(req).getBody();  // 返回可自动提交的支付表单
        throw new UnsupportedOperationException("未配置真实支付宝商户信息，请先开通商户号并填写 AlipayConfig");
    }

    /**
     * 回调验签。
     * mock 模式：重算 paymentNo/amount/mockKey 的签名并比对；
     * 真实模式：AlipaySignature.rsaCheckV1(params, alipayPublicKey, "UTF-8", "RSA2")。
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
                log.warn("支付宝 mock 回调验签失败: paymentNo={}", paymentNo);
            }
            return ok;
        }
        // ---------- 真实支付宝验签接入点 ----------
        // return AlipaySignature.rsaCheckV1(params, alipayConfig.getAlipayPublicKey(), "UTF-8", "RSA2");
        throw new UnsupportedOperationException("未配置真实支付宝公钥，无法验签");
    }
}
