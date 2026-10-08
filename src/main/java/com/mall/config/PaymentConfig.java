package com.mall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 支付总配置（对应 application.yml 的 pay.*）。
 *
 * <p><b>mock-enabled（模拟支付开关）是本阶段的关键设计</b>：
 * <ul>
 *   <li>真实接入支付宝/微信需要商户号、应用 ID、密钥与备案域名，本地开发环境无法开通，
 *       因此默认开启"模拟支付"：网关返回模拟支付地址，前端可模拟扫码并调用回调接口
 *       完成"支付流水 + 订单状态"的完整闭环演示；</li>
 *   <li>生产环境把 mock-enabled 置为 false 并填好 alipay/wechat 真实配置即可切换，
 *       代码结构（网关接口 + 工厂 + 回调验签）与真实支付一致。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "pay")
public class PaymentConfig {

    /** 是否开启模拟支付（本地开发默认 true；生产置 false 走真实 SDK） */
    private boolean mockEnabled = true;

    /** 支付凭证有效期（秒），前端用于倒计时 */
    private int expireSeconds = 1800;

    /** 支付回调地址（支付平台异步通知用） */
    private String callbackUrl;

    /** 模拟签名密钥：仅 mock 模式使用，真实模式下由各支付 SDK 验签 */
    private String mockKey = "mock-pay-key-2026";

    /**
     * 计算 mock 签名（演示"验签"流程用，非生产安全方案）。
     *
     * <p>规则：SHA-256(paymentNo + "&" + amount + "&" + mockKey)，
     * 前端在模拟支付时携带该签名，后端回调时重新计算并比对，防止参数被随意篡改。
     */
    public String mockSign(String paymentNo, String amount) {
        String raw = paymentNo + "&" + amount + "&" + mockKey;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
