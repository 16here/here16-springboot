package com.mall.controller;

import com.mall.common.Result;
import com.mall.entity.Category;
import com.mall.service.CategoryService;
import lombok.RequiredArgsConstructor;
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
 * 分类管理接口（管理端，/api/admin/** 已受保护）。
 *
 * <p>说明：阶段 1 中 /api/admin/** 仅要求登录（管理端认证与角色细分属后续阶段），
 * 待管理端认证上线后将收紧为 ROLE_ADMIN。
 */
@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryService categoryService;

    /** 平铺分类列表（管理端表格使用；树形结构走公开接口 GET /api/categories） */
    @GetMapping
    public Result<List<Category>> list() {
        return Result.success(categoryService.flatList());
    }

    /** 新增分类（顶级或子分类） */
    @PostMapping
    public Result<Category> create(@RequestBody Category category) {
        return Result.success(categoryService.create(category));
    }

    /** 更新分类 */
    @PutMapping("/{id}")
    public Result<Category> update(@PathVariable Long id, @RequestBody Category category) {
        return Result.success(categoryService.update(id, category));
    }

    /** 删除分类（有子分类或商品时拒绝） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success();
    }
}
