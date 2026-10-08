package com.mall.controller;

import com.mall.common.Result;
import com.mall.dto.CategoryTreeVO;
import com.mall.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类查询接口（用户端，公开）。
 *
 * <p>GET /api/categories：返回完整分类树，前端导航与分类页共用。
 */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /** 获取分类树（顶级分类为根，含子分类） */
    @GetMapping
    public Result<List<CategoryTreeVO>> tree() {
        return Result.success(categoryService.tree());
    }
}
