package com.mall.controller;

import com.mall.common.BusinessException;
import com.mall.common.CurrentUser;
import com.mall.common.Result;
import com.mall.dto.PaymentRequestDTO;
import com.mall.dto.PaymentResponseVO;
import com.mall.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户支付接口（/api/pay/**，需登录）。
 *
 * <p>支付是用户私有行为：userId 一律从登录上下文取，不接受前端传参。
 * 注意：回调接口（/api/pay/callback/**）在 {@code PaymentCallbackController}
 * 中单独暴露并对支付平台放行（见 SecurityConfig）。
 */
@RestController
@RequestMapping("/api/pay")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /** 发起支付：生成支付流水 + 返回支付凭证（真实支付链接 / mock 模拟标识） */
    @PostMapping("/{orderId}")
    public Result<PaymentResponseVO> create(@PathVariable Long orderId,
                                            @Valid @RequestBody PaymentRequestDTO dto) {
        return Result.success(paymentService.createPayment(currentUserId(), orderId, dto.getPayMethod()));
    }

    /** 查询支付状态（前端轮询 / 支付结果页展示） */
    @GetMapping("/status")
    public Result<PaymentResponseVO> status(@RequestParam String paymentNo) {
        return Result.success(paymentService.queryStatus(currentUserId(), paymentNo));
    }

    /** 从 Spring Security 上下文提取当前登录用户 ID */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser current)) {
            throw new BusinessException(BusinessException.UNAUTHORIZED, "未登录或登录已过期");
        }
        return current.userId();
    }
}
