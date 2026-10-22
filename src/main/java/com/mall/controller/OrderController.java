package com.mall.controller;

import com.mall.common.BusinessException;
import com.mall.common.CurrentUser;
import com.mall.common.PageResult;
import com.mall.common.RateLimit;
import com.mall.common.Result;
import com.mall.dto.OrderCreateDTO;
import com.mall.dto.OrderQueryDTO;
import com.mall.dto.OrderVO;
import com.mall.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户订单接口（/api/orders/**，需登录）。
 *
 * <p>订单是用户私有数据：userId 一律从登录上下文取，不接受前端传参。
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /** 创建订单（校验库存、生成订单号、快照落库、扣库存、清购物车） */
    @RateLimit(key = "order:create", limit = 20, windowSeconds = 60, message = "下单太频繁，请稍后再试")
    @PostMapping
    public Result<OrderVO> create(@Valid @RequestBody OrderCreateDTO dto) {
        return Result.success(orderService.create(currentUserId(), dto));
    }

    /** 我的订单列表（状态筛选 + 分页） */
    @GetMapping
    public Result<PageResult<OrderVO>> list(@Valid OrderQueryDTO dto) {
        return Result.success(orderService.list(currentUserId(), dto));
    }

    /** 订单详情 */
    @GetMapping("/{id}")
    public Result<OrderVO> detail(@PathVariable Long id) {
        return Result.success(orderService.detail(currentUserId(), id));
    }

    /** 取消订单（仅待付款/待发货可取消，取消后恢复库存） */
    @PostMapping("/{id}/cancel")
    public Result<OrderVO> cancel(@PathVariable Long id) {
        return Result.success(orderService.cancel(currentUserId(), id));
    }

    /** 确认收货（仅待收货可确认 → 已完成；阶段 5 接入状态机） */
    @PostMapping("/{id}/confirm")
    public Result<OrderVO> confirm(@PathVariable Long id) {
        return Result.success(orderService.confirm(currentUserId(), id));
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
