package com.mall.controller;

import com.mall.common.BusinessException;
import com.mall.common.CurrentUser;
import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.dto.AfterSaleCreateDTO;
import com.mall.dto.AfterSaleVO;
import com.mall.service.AfterSaleService;
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
 * 用户售后接口（/api/after-sales/**，需登录，阶段 7）。
 *
 * <p>售后单是用户私有数据：userId 一律从登录上下文取，不接受前端传参。
 */
@RestController
@RequestMapping("/api/after-sales")
@RequiredArgsConstructor
public class AfterSaleController {

    private final AfterSaleService afterSaleService;

    /** 申请售后（仅退款 / 退货退款，金额 ≤ 订单实付） */
    @PostMapping
    public Result<AfterSaleVO> apply(@Valid @RequestBody AfterSaleCreateDTO dto) {
        return Result.success(afterSaleService.apply(currentUserId(), dto));
    }

    /** 我的售后列表（状态筛选 + 分页） */
    @GetMapping
    public Result<PageResult<AfterSaleVO>> list(@RequestParam(required = false) Integer status,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(afterSaleService.list(currentUserId(), status, page, pageSize));
    }

    /** 售后详情（含订单商品明细） */
    @GetMapping("/{id}")
    public Result<AfterSaleVO> detail(@PathVariable Long id) {
        return Result.success(afterSaleService.detail(currentUserId(), id));
    }

    /** 撤销售后申请（仅待审核可撤销） */
    @PostMapping("/{id}/cancel")
    public Result<AfterSaleVO> cancel(@PathVariable Long id) {
        return Result.success(afterSaleService.cancelApply(currentUserId(), id));
    }

    /** 填写退货物流单号（仅退货退款且待寄回可填） */
    @PostMapping("/{id}/logistics")
    public Result<AfterSaleVO> fillLogistics(@PathVariable Long id,
                                             @RequestParam String logisticsNo) {
        return Result.success(afterSaleService.fillLogistics(currentUserId(), id, logisticsNo));
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
