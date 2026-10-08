package com.mall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信支付配置（对应 application.yml 的 pay.wechat.*）。
 *
 * <p>字段与微信支付商户平台一一对应；本地开发（mock 模式）可为空，
 * 接入真实微信支付时填写（V3 版 API：商户号 + API v3 密钥 + 商户证书序列号 + 私钥路径）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "pay.wechat")
public class WechatPayConfig {

    /** 微信支付商户号 */
    private String merchantId;

    /** API v3 密钥 */
    private String apiKey;

    /** 商户证书序列号 */
    private String mchSerialNo;

    /** 商户私钥文件路径（apiclient_key.pem） */
    private String privateKeyPath;
}
