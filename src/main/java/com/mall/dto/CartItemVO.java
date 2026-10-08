package com.mall.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 购物车单项视图对象（列表页单行展示）。
 */
@Data
public class CartItemVO {

    /** 购物车项 ID（前端增删改用它） */
    private Long cartId;

    /** 商品 ID */
    private Long productId;

    /** 规格 ID；0=未选规格 */
    private Long skuId;

    /** 数量 */
    private Integer quantity;

    /** 商品名称 */
    private String productName;

    /** 商品主图 */
    private String productImage;

    /** 规格名称；无规格为 null */
    private String skuName;

    /** 当前单价（有规格用规格价，否则用商品价） */
    private BigDecimal price;

    /** 可购库存（有规格用规格库存，否则用商品库存） */
    private Integer stock;
}
