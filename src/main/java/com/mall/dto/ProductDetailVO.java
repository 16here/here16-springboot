package com.mall.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品详情视图对象（详情页使用：图片 + 规格）。
 */
@Data
public class ProductDetailVO {

    /** 商品 ID */
    private Long id;

    /** 商品 SKU 编码 */
    private String sku;

    /** 商品名称 */
    private String name;

    /** 商品描述 */
    private String description;

    /** 售价 */
    private BigDecimal price;

    /** 库存 */
    private Integer stock;

    /** 状态：1=上架，0=下架 */
    private Integer status;

    /** 分类 ID */
    private Long categoryId;

    /** 分类名称 */
    private String categoryName;

    /** 商品图地址列表（按排序） */
    private List<String> images;

    /** 规格列表 */
    private List<SkuItemVO> skus;

    /**
     * 规格视图对象。
     */
    @Data
    public static class SkuItemVO {

        /** 规格 ID */
        private Long id;

        /** 规格名，如 红色/M */
        private String name;

        /** 规格售价 */
        private BigDecimal price;

        /** 规格库存 */
        private Integer stock;

        /** 规格图片（可选） */
        private String image;
    }
}
