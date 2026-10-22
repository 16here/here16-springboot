package com.mall.controller;

import com.mall.common.BusinessException;
import com.mall.common.CurrentUser;
import com.mall.common.PageResult;
import com.mall.common.RateLimit;
import com.mall.common.Result;
import com.mall.dto.CouponUseDTO;
import com.mall.dto.CouponVO;
import com.mall.dto.UserCouponVO;
import com.mall.service.CouponService;
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

import java.math.BigDecimal;
import java.util.List;

/**
 * 用户端优惠券接口（阶段 8）。
 *
 * <p>领券中心列表（GET /api/coupons/center）为公开接口，未登录也能逛；
 * 领取 / 我的券 / 可用券必须登录，userId 一律从登录上下文取（不接受前端传参）。
 */
@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    /** 领券中心：上架且未过期的券模板（公开；已登录时附带"是否已领"按钮态） */
    @GetMapping("/center")
    public Result<List<CouponVO>> center() {
        return Result.success(couponService.listCenter(tryUserId()));
    }

    /** 领取优惠券（需登录；限流防脚本批量刷券） */
    @RateLimit(key = "coupon:receive", limit = 10, windowSeconds = 60, message = "领券太频繁，请稍后再试")
    @PostMapping("/{couponId}/receive")
    public Result<UserCouponVO> receive(@PathVariable Long couponId) {
        return Result.success(couponService.receive(currentUserId(), couponId));
    }

    /** 我的优惠券（需登录；statusTab：0=未使用 2=已使用 3=已过期；null=全部） */
    @GetMapping("/mine")
    public Result<PageResult<UserCouponVO>> mine(@RequestParam(required = false) Integer status,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(couponService.listMy(currentUserId(), status, page, pageSize));
    }

    /** 可用券列表（需登录；按订单商品金额筛选门槛，结算页展示） */
    @GetMapping("/available")
    public Result<List<UserCouponVO>> available(@RequestParam BigDecimal amount) {
        return Result.success(couponService.listAvailable(currentUserId(), amount));
    }

    /** 下单前试算一张券的优惠金额（结算页选中券时调用，校验但不落库） */
    @PostMapping("/preview")
    public Result<BigDecimal> preview(@RequestBody CouponUseDTO dto,
                                      @RequestParam BigDecimal amount) {
        return Result.success(couponService.calcDiscount(currentUserId(), dto.getUserCouponId(),
                amount, amount));
    }

    /** 从 Spring Security 上下文提取当前登录用户 ID */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser current)) {
            throw new BusinessException(BusinessException.UNAUTHORIZED, "未登录或登录已过期");
        }
        return current.userId();
    }

    /** 尝试取用户 ID（未登录返回 null，供公开接口区分按钮态） */
    private Long tryUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser current) {
            return current.userId();
        }
        return null;
    }
}
