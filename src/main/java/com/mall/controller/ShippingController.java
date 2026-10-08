package com.mall.controller;

import com.mall.common.CurrentUser;
import com.mall.common.Result;
import com.mall.dto.ShippingVO;
import com.mall.service.ShippingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端物流查询接口（/api/shipping/**，阶段 9）。
 *
 * <p>SecurityConfig 已将 /api/shipping/** 纳入"需登录"，本接口再做
 * <b>订单归属校验</b>（只能查自己订单的物流），防止越权查看他人订单。
 */
@RestController
@RequestMapping("/api/shipping")
@RequiredArgsConstructor
public class ShippingController {

    private final ShippingService shippingService;

    /** 查看订单物流（GET /api/shipping/{orderId}）：返回运单信息 + 轨迹时间轴 */
    @GetMapping("/{orderId}")
    public Result<ShippingVO> get(@PathVariable Long orderId) {
        return Result.success(shippingService.getByOrder(currentUserId(), orderId));
    }

    /** 从 Spring Security 上下文取当前登录用户 ID（JwtAuthFilter 已写入） */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser current)) {
            throw new IllegalStateException("未登录");
        }
        return current.userId();
    }
}
