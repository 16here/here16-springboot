package com.mall.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品列表项视图对象（列表页卡片使用）。
 */
@Data
public class ProductListVO {

    /** 商品 ID */
    private Long id;

    /** 商品 SKU 编码 */
    private String sku;

    /** 商品名称 */
    private String name;

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

    /** 主图（第一张商品图） */
    private String cover;
}
