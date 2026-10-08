package com.mall.controller;

import com.mall.common.BusinessException;
import com.mall.common.CurrentUser;
import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.dto.ReviewCreateDTO;
import com.mall.dto.ReviewStatsVO;
import com.mall.dto.ReviewVO;
import com.mall.service.ReviewService;
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
 * 评价接口（阶段 7）。
 *
 * <p>GET 商品评价列表 / 评分统计为公开接口（商品详情页无需登录可看）；
 * POST 发表评价需登录，且 userId 一律从登录上下文取（不接受前端传参）。
 */
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    /** 发表评价（仅已完成订单可评，默认待审核） */
    @PostMapping
    public Result<ReviewVO> create(@Valid @RequestBody ReviewCreateDTO dto) {
        return Result.success(reviewService.create(currentUserId(), dto));
    }

    /** 商品已发布评价列表（公开，分页） */
    @GetMapping("/product/{productId}")
    public Result<PageResult<ReviewVO>> listByProduct(@PathVariable Long productId,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(reviewService.listByProduct(productId, page, pageSize));
    }

    /** 商品评分统计（公开：平均分 + 星级分布） */
    @GetMapping("/product/{productId}/stats")
    public Result<ReviewStatsVO> stats(@PathVariable Long productId) {
        return Result.success(reviewService.stats(productId));
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
