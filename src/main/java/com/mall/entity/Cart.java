package com.mall.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 购物车实体，对应 carts 表。
 *
 * <p>说明：skuId=0 表示"未选择规格"（商品无规格时）。
 * 以下字段为非表字段，列表查询 LEFT JOIN 商品/规格后填充，
 * 用于组装购物车列表视图，避免逐行再查一次数据库：
 * productName / productStatus / productStock / productImage / productPrice
 * skuName / skuPrice / skuStock
 */
@Data
public class Cart {

    /** 购物车项主键 */
    private Long id;

    /** 所属用户 ID */
    private Long userId;

    /** 商品 ID */
    private Long productId;

    /** 规格 ID；0=未选规格（无规格商品） */
    private Long skuId;

    /** 数量 */
    private Integer quantity;

    /** 创建时间 */
    private LocalDateTime createdAt;

    // ---------- 以下为非表字段（JOIN 填充） ----------

    /** 商品名称 */
    private String productName;

    /** 商品状态：1=上架，0=下架 */
    private Integer productStatus;

    /** 商品总库存 */
    private Integer productStock;

    /** 商品主图（第一张图） */
    private String productImage;

    /** 商品价格（无规格时的兜底单价） */
    private BigDecimal productPrice;

    /** 规格名称，如 红色/M；无规格时为 null */
    private String skuName;

    /** 规格价格 */
    private BigDecimal skuPrice;

    /** 规格库存 */
    private Integer skuStock;
}
