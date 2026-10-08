package com.mall.controller;

import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.dto.ProductCreateDTO;
import com.mall.dto.ProductListVO;
import com.mall.dto.ProductQueryDTO;
import com.mall.dto.ProductUpdateDTO;
import com.mall.dto.StatusUpdateDTO;
import com.mall.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品管理接口（管理端，/api/admin/** 已受保护）。
 *
 * <p>说明：阶段 1 中 /api/admin/** 仅要求登录（管理端认证与角色细分属后续阶段），
 * 待管理端认证上线后将收紧为 ROLE_ADMIN。
 */
@RestController
@RequestMapping("/api/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductService productService;

    /** 商品列表（含下架商品，管理端分页） */
    @GetMapping
    public Result<PageResult<ProductListVO>> page(@Valid ProductQueryDTO query) {
        return Result.success(productService.adminPage(query));
    }

    /** 新增商品 */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody ProductCreateDTO dto) {
        return Result.success(productService.create(dto));
    }

    /** 更新商品（全量更新基本信息 + 图片 + 规格） */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ProductUpdateDTO dto) {
        productService.update(id, dto);
        return Result.success();
    }

    /** 删除商品（连带图片与规格） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return Result.success();
    }

    /** 商品上下架 */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody StatusUpdateDTO dto) {
        productService.updateStatus(id, dto.getStatus());
        return Result.success();
    }
}
