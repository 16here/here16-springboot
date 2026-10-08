package com.mall.controller;

import com.mall.common.BusinessException;
import com.mall.common.CurrentUser;
import com.mall.common.Result;
import com.mall.dto.CartAddDTO;
import com.mall.dto.CartUpdateDTO;
import com.mall.dto.CartVO;
import com.mall.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 购物车接口（/api/cart/**，SecurityConfig 已配置为需登录）。
 *
 * <p>说明：购物车是用户私有数据，所有方法从 SecurityContext 取当前用户 ID，
 * 不接受前端传 userId（防止越权操作他人购物车）。
 */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    /** 查询购物车（明细 + 总件数 + 总金额） */
    @GetMapping
    public Result<CartVO> list() {
        return Result.success(cartService.list(currentUserId()));
    }

    /** 添加购物车（重复添加同一商品/规格自动合并数量） */
    @PostMapping
    public Result<CartVO> add(@Valid @RequestBody CartAddDTO dto) {
        return Result.success(cartService.add(currentUserId(), dto));
    }

    /** 更新数量 */
    @PutMapping("/{id}")
    public Result<CartVO> updateQuantity(@PathVariable Long id, @Valid @RequestBody CartUpdateDTO dto) {
        return Result.success(cartService.updateQuantity(currentUserId(), id, dto.getQuantity()));
    }

    /** 删除单项 */
    @DeleteMapping("/{id}")
    public Result<CartVO> remove(@PathVariable Long id) {
        return Result.success(cartService.remove(currentUserId(), id));
    }

    /** 清空购物车 */
    @DeleteMapping
    public Result<Void> clear() {
        cartService.clear(currentUserId());
        return Result.success();
    }

    /** 登录合并：把游客本地购物车批量并入服务器 */
    @PostMapping("/merge")
    public Result<CartVO> merge(@Valid @RequestBody List<CartAddDTO> items) {
        return Result.success(cartService.merge(currentUserId(), items));
    }

    /** 从 Spring Security 上下文提取当前登录用户 ID（由 JwtAuthFilter 写入） */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser current)) {
            throw new BusinessException(BusinessException.UNAUTHORIZED, "未登录或登录已过期");
        }
        return current.userId();
    }
}
