package com.mall.controller;

import com.mall.common.CurrentUser;
import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.dto.ProductDetailVO;
import com.mall.dto.ProductListVO;
import com.mall.dto.ProductQueryDTO;
import com.mall.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品查询接口（用户端，公开）。
 *
 * <ul>
 *   <li>GET /api/products：商品分页（关键词、分类筛选、价格排序）</li>
 *   <li>GET /api/products/{id}：商品详情（图片 + 规格）</li>
 *   <li>GET /api/products/{id}/stock：商品级实时可卖库存（阶段 6，无规格商品）</li>
 *   <li>GET /api/skus/{id}/stock：SKU 实时可卖库存（阶段 6）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /** 商品分页查询（仅上架商品） */
    @GetMapping("/products")
    public Result<PageResult<ProductListVO>> page(@Valid ProductQueryDTO query) {
        return Result.success(productService.page(query));
    }

    /**
     * 商品详情（阶段 10 起同时记录浏览行为）。
     * 详情接口本身公开（未登录也能看），userId 可空：登录用户的行为
     * 参与个性化推荐，游客行为只参与热度统计。
     */
    @GetMapping("/products/{id}")
    public Result<ProductDetailVO> detail(@PathVariable Long id) {
        productService.recordView(id, currentUserId());
        return Result.success(productService.detail(id));
    }

    /** 商品级实时可卖库存（无规格商品） */
    @GetMapping("/products/{id}/stock")
    public Result<Integer> productStock(@PathVariable Long id) {
        return Result.success(productService.getProductStock(id));
    }

    /** SKU 实时可卖库存 */
    @GetMapping("/skus/{id}/stock")
    public Result<Integer> skuStock(@PathVariable Long id) {
        return Result.success(productService.getSkuStock(id));
    }

    /** 从 SecurityContext 取当前用户 ID（未登录返回 null，浏览照常记录为游客行为） */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser cu) {
            return cu.userId();
        }
        return null;
    }
}
