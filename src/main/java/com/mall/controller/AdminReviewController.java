package com.mall.controller;

import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.dto.ReviewVO;
import com.mall.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端评价接口（/api/admin/reviews/**，仅 ADMIN，阶段 7）。
 *
 * <p>运营职责：查看全部评价（含待审核）、审核通过/隐藏、商家回复。
 * 权限由 SecurityConfig 的 /api/admin/** hasRole("ADMIN") 统一保护。
 */
@RestController
@RequestMapping("/api/admin/reviews")
@RequiredArgsConstructor
public class AdminReviewController {

    private final ReviewService reviewService;

    /** 评价分页（状态筛选：null 全部 / 0 待审核 / 1 已发布 / 2 已隐藏） */
    @GetMapping
    public Result<PageResult<ReviewVO>> list(@RequestParam(required = false) Integer status,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(reviewService.adminList(status, page, pageSize));
    }

    /** 审核评价：status=1 审核通过（发布展示），status=2 隐藏 */
    @PostMapping("/{id}/audit")
    public Result<ReviewVO> audit(@PathVariable Long id, @RequestParam Integer status) {
        return Result.success(reviewService.audit(id, status));
    }

    /** 商家回复评价（body: {"reply": "感谢您的支持"}） */
    @PostMapping("/{id}/reply")
    public Result<ReviewVO> reply(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Result.success(reviewService.reply(id, body.get("reply")));
    }
}
