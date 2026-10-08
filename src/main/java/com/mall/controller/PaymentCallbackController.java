package com.mall.controller;

import com.mall.dto.PaymentCallbackDTO;
import com.mall.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 支付回调接口（/api/pay/callback/**，对支付平台放行，无需登录）。
 *
 * <p>真实流程：用户在支付平台完成付款后，支付平台（支付宝/微信）异步
 * POST 回调本接口，携带支付结果与签名；本接口验签后更新支付流水与订单状态。
 *
 * <p>返回约定：支付宝要求回调必须返回纯文本 {@code success}（否则会重复通知）。
 * mock 模式下前端"模拟扫码支付"也调用本接口，完成开发环境的支付闭环演示。
 */
@Slf4j
@RestController
@RequestMapping("/api/pay/callback")
@RequiredArgsConstructor
public class PaymentCallbackController {

    private final PaymentService paymentService;

    /** 支付宝异步回调 */
    @PostMapping("/alipay")
    public String alipay(@RequestParam Map<String, String> params) {
        return handle("alipay", params);
    }

    /** 微信支付异步回调 */
    @PostMapping("/wechat")
    public String wechat(@RequestParam Map<String, String> params) {
        return handle("wechat", params);
    }

    /** 统一处理：调用 PaymentService.handleCallback，返回支付平台约定的文本 */
    private String handle(String payMethod, Map<String, String> params) {
        try {
            PaymentCallbackDTO result = paymentService.handleCallback(payMethod, params);
            if (Boolean.TRUE.equals(result.getSuccess())) {
                return "success";
            }
            log.warn("支付回调处理失败: payMethod={}, message={}", payMethod, result.getMessage());
            return "fail";
        } catch (Exception e) {
            log.error("支付回调处理异常: payMethod={}", payMethod, e);
            return "fail";
        }
    }
}
