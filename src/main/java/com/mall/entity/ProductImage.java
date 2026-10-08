package com.mall.entity;

import lombok.Data;

import java.io.Serializable;

/**
 * 商品图片实体，对应表 product_images。
 */
@Data
public class ProductImage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 图片 ID */
    private Long id;

    /** 所属商品 ID */
    private Long productId;

    /** 图片地址 */
    private String imageUrl;

    /** 排序值，越小越靠前 */
    private Integer sort;
}
