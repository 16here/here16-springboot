package com.mall.controller;

import com.mall.common.CurrentUser;
import com.mall.common.Result;
import com.mall.dto.ProductListVO;
import com.mall.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收藏接口（阶段 11 需求 3，需登录，SecurityConfig 已保护 /api/favorites/**）。
 *
 * <ul>
 *   <li>GET    /api/favorites：收藏夹列表（最新收藏在前，含商品信息+主图）</li>
 *   <li>POST   /api/favorites/{productId}：收藏商品（幂等）</li>
 *   <li>DELETE /api/favorites/{productId}：取消收藏（幂等）</li>
 *   <li>GET    /api/favorites/{productId}/status：是否已收藏（详情页按钮初始状态）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    /** 收藏夹列表 */
    @GetMapping
    public Result<List<ProductListVO>> list() {
        return Result.success(favoriteService.list(currentUserId()));
    }

    /** 收藏商品（幂等） */
    @PostMapping("/{productId}")
    public Result<Void> add(@PathVariable Long productId) {
        favoriteService.add(currentUserId(), productId);
        return Result.success();
    }

    /** 取消收藏（幂等） */
    @DeleteMapping("/{productId}")
    public Result<Void> remove(@PathVariable Long productId) {
        favoriteService.remove(currentUserId(), productId);
        return Result.success();
    }

    /** 是否已收藏 */
    @GetMapping("/{productId}/status")
    public Result<Boolean> status(@PathVariable Long productId) {
        return Result.success(favoriteService.isFavorite(currentUserId(), productId));
    }

    /**
     * 从 SecurityContext 取当前用户 ID。
     * 收藏接口已被 Security 保护（需登录），此处 userId 必不为空；
     * 若为空说明过滤器链路异常，抛 401 由全局异常处理器兜底。
     */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser cu) {
            return cu.userId();
        }
        throw new RuntimeException("未登录");
    }
}
