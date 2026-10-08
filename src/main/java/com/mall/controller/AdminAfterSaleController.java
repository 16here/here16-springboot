package com.mall.controller;

import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.dto.AfterSaleAuditDTO;
import com.mall.dto.AfterSaleVO;
import com.mall.service.AfterSaleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端售后接口（/api/admin/after-sales/**，仅 ADMIN，阶段 7）。
 *
 * <p>运营职责：售后单列表/详情、审核（通过/拒绝）、确认收货退款（退货退款链路）。
 * 权限由 SecurityConfig 的 /api/admin/** hasRole("ADMIN") 统一保护。
 */
@RestController
@RequestMapping("/api/admin/after-sales")
@RequiredArgsConstructor
public class AdminAfterSaleController {

    private final AfterSaleService afterSaleService;

    /** 售后分页（类型/状态筛选） */
    @GetMapping
    public Result<PageResult<AfterSaleVO>> list(@RequestParam(required = false) Integer type,
                                                @RequestParam(required = false) Integer status,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(afterSaleService.adminList(type, status, page, pageSize));
    }

    /** 售后详情（含订单商品明细） */
    @GetMapping("/{id}")
    public Result<AfterSaleVO> detail(@PathVariable Long id) {
        return Result.success(afterSaleService.adminDetail(id));
    }

    /**
     * 审核售后单（body: {"approve": true/false, "remark": "意见"}）。
     * 仅退款通过 → 直接退款；退货退款通过 → 待寄回；拒绝 → 已拒绝。
     */
    @PostMapping("/{id}/audit")
    public Result<AfterSaleVO> audit(@PathVariable Long id, @Valid @RequestBody AfterSaleAuditDTO dto) {
        return Result.success(afterSaleService.audit(id, dto));
    }

    /** 确认收货退款（退货退款链路：待寄回 → 已完成），发货后点击 */
    @PostMapping("/{id}/refund")
    public Result<AfterSaleVO> confirmRefund(@PathVariable Long id) {
        return Result.success(afterSaleService.confirmRefund(id));
    }
}
