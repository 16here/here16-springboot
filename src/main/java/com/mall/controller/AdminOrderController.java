package com.mall.controller;

import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.dto.OrderQueryDTO;
import com.mall.dto.OrderVO;
import com.mall.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端订单接口（/api/admin/orders/**）。
 *
 * <p>阶段 5：接口从"需登录"收紧为"需 ADMIN 角色"（SecurityConfig 的
 * hasRole("ADMIN")，配合 users.role 字段与 JWT 角色 claim）。
 * 普通用户访问返回 403。
 */
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;

    /** 管理端订单列表（状态筛选 + 订单号搜索，带下单人用户名） */
    @GetMapping
    public Result<PageResult<OrderVO>> list(@Valid OrderQueryDTO dto) {
        return Result.success(orderService.adminList(dto));
    }

    /** 管理端订单详情（含明细与状态时间轴） */
    @GetMapping("/{id}")
    public Result<OrderVO> detail(@PathVariable Long id) {
        return Result.success(orderService.adminDetail(id));
    }

    /**
     * 发货（SHIP 事件，仅待发货订单可发货 → 待收货）。
     * 阶段 5：发货弹窗可填写物流单号，随请求传入。
     */
    @PostMapping("/{id}/ship")
    public Result<Void> ship(@PathVariable Long id,
                             @RequestParam(required = false) String shippingNo) {
        orderService.ship(id, shippingNo);
        return Result.success();
    }

    /**
     * 关闭订单（CLOSE 事件，仅待付款订单可关 → 已取消，恢复库存）。
     * 阶段 5 新增：用于处理恶意下单/占用库存等问题订单。
     */
    @PostMapping("/{id}/close")
    public Result<Void> close(@PathVariable Long id) {
        orderService.close(id);
        return Result.success();
    }
}
