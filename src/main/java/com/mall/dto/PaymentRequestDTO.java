package com.mall.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发起支付请求入参。
 *
 * <p>前端只传支付方式（alipay / wechat）；订单号在 URL 路径里，
 * 金额、归属等全部由后端从订单读取——前端无法篡改支付金额。
 */
@Data
public class PaymentRequestDTO {

    /** 支付方式：alipay=支付宝 / wechat=微信 */
    @NotBlank(message = "支付方式不能为空")
    private String payMethod;
}
