package com.mall.controller;

import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.entity.Payment;
import com.mall.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端支付流水接口（/api/admin/payments/**，需登录，SecurityConfig 已整体保护 /api/admin/**）。
 *
 * <p>阶段 4 清单未列出管理端支付接口，但管理端"支付流水查询、对账"页面必须有接口支撑，
 * 按阶段 1 起的管理端惯例补充（代码注释与《后端代码梳理》已如实说明）。
 */
@RestController
@RequestMapping("/api/admin/payments")
@RequiredArgsConstructor
public class AdminPaymentController {

    private final PaymentRepository paymentRepository;

    /**
     * 支付流水分页查询（对账）。
     *
     * @param payMethod 支付方式（alipay/wechat，可空=全部）
     * @param status    流水状态（可空=全部）
     * @param keyword   订单号 / 支付流水号模糊搜索（可空）
     */
    @GetMapping
    public Result<PageResult<Payment>> page(@RequestParam(required = false) String payMethod,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int pageSize) {
        int offset = (page - 1) * pageSize;
        var list = paymentRepository.adminPage(payMethod, status, keyword, offset, pageSize);
        long total = paymentRepository.adminCount(payMethod, status, keyword);
        return Result.success(new PageResult<>(total, list));
    }
}
