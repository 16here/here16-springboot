package com.mall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 支付宝支付配置（对应 application.yml 的 pay.alipay.*）。
 *
 * <p>字段与支付宝开放平台一一对应；本地开发（mock 模式）这些字段可为空，
 * 接入真实支付宝时填写即可（详见 PaymentConfig 的 mock-enabled 说明）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "pay.alipay")
public class AlipayConfig {

    /** 支付宝开放平台应用 ID（真实接入必填） */
    private String appId;

    /** 应用私钥（真实接入必填，用于签名请求） */
    private String privateKey;

    /** 支付宝公钥（真实接入必填，用于验签回调） */
    private String alipayPublicKey;

    /** 支付宝网关地址（沙箱环境为 openapi.alipaydev.com/gateway.do） */
    private String gateway = "https://openapi.alipay.com/gateway.do";
}
