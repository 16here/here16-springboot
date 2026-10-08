package com.mall.controller;

import com.mall.common.CurrentUser;
import com.mall.common.Result;
import com.mall.dto.RecommendVO;
import com.mall.service.RecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推荐接口（阶段 10，用户端）。
 *
 * <p>两个推荐位：
 * <ul>
 *   <li><code>/api/recommend/home</code>：首页推荐位——已登录给个性化"猜你喜欢"，
 *       未登录给热度"热门推荐"（GET 放行，未登录也能逛）；</li>
 *   <li><code>/api/recommend/product/{id}</code>：商品详情"相关推荐"——
 *       物品协同过滤（看过此商品的用户还看了），GET 放行。</li>
 * </ul>
 *
 * <p>userId 一律从 Spring Security 上下文取（当前用户），
 * 未登录时上下文为 null → 服务层自动走热度/公共推荐。
 */
@RestController
@RequestMapping("/api/recommend")
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendService recommendService;

    /** 首页推荐位：登录=猜你喜欢（个性化），未登录=热门推荐（热度） */
    @GetMapping("/home")
    public Result<RecommendVO> home(@RequestParam(defaultValue = "0") int limit) {
        return Result.success(recommendService.home(currentUserId(), limit));
    }

    /** 商品详情相关推荐（物品协同过滤，未登录同样可用） */
    @GetMapping("/product/{id}")
    public Result<RecommendVO> related(@PathVariable Long id,
                                       @RequestParam(defaultValue = "0") int limit) {
        return Result.success(recommendService.related(id, currentUserId(), limit));
    }

    /** 从 SecurityContext 取当前用户 ID（未登录返回 null） */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser cu) {
            return cu.userId();
        }
        return null;
    }
}
