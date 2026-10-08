package com.mall.controller;

import com.mall.common.CurrentUser;
import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.dto.ShippingCreateDTO;
import com.mall.dto.ShippingVO;
import com.mall.service.ShippingService;
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
 * 管理端物流接口（/api/admin/shipping/**，阶段 9）。
 *
 * <p>SecurityConfig 对 /api/admin/** 统一要求 ADMIN 角色（阶段 5 起），
 * 普通用户访问返回 403。职责：发货（创建运单）、运单列表、运单详情、手动同步轨迹。
 */
@RestController
@RequestMapping("/api/admin/shipping")
@RequiredArgsConstructor
public class AdminShippingController {

    private final ShippingService shippingService;

    /**
     * 发货（POST /api/admin/shipping/ship，body：{orderId, shippingCompany, trackingNo}）。
     * 创建运单 + 初始轨迹 + 订单状态流转（待发货 → 待收货）。
     */
    @PostMapping("/ship")
    public Result<ShippingVO> ship(@Valid @RequestBody ShippingCreateDTO dto) {
        return Result.success(shippingService.ship(currentAdminId(), dto));
    }

    /** 运单列表（GET /api/admin/shipping?status=&page=&pageSize=，状态筛选 + 收货人信息） */
    @GetMapping
    public Result<PageResult<ShippingVO>> list(@RequestParam(required = false) Integer status,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(shippingService.adminList(status, page, pageSize));
    }

    /** 运单详情（GET /api/admin/shipping/{id}，含轨迹时间轴） */
    @GetMapping("/{id}")
    public Result<ShippingVO> detail(@PathVariable Long id) {
        return Result.success(shippingService.adminDetail(id));
    }

    /** 手动同步物流轨迹（POST /api/admin/shipping/{id}/sync） */
    @PostMapping("/{id}/sync")
    public Result<Void> sync(@PathVariable Long id) {
        shippingService.syncTrace(id);
        return Result.success();
    }

    /** 从 Spring Security 上下文取当前管理员 ID（记录操作人） */
    private Long currentAdminId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser current)) {
            throw new IllegalStateException("未登录");
        }
        return current.userId();
    }
}
