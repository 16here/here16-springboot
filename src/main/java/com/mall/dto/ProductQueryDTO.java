package com.mall.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 商品列表查询参数（用户端与管理端共用）。
 */
@Data
public class ProductQueryDTO {

    /** 关键词：匹配商品名称或 SKU 编码 */
    private String keyword;

    /** 分类 ID：按分类筛选（不含子分类） */
    private Long categoryId;

    /**
     * 排序方式（Service 层映射为 SQL 白名单）：
     * default=默认（最新优先）、price_asc=价格升序、price_desc=价格降序
     */
    private String sortBy;

    /** 页码，从 1 开始 */
    @Min(value = 1, message = "页码不能小于 1")
    private Integer page = 1;

    /** 每页数量 */
    @Min(value = 1, message = "每页数量不能小于 1")
    @Max(value = 100, message = "每页数量不能超过 100")
    private Integer pageSize = 12;
}
