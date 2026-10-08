package com.mall.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商品规格实体，对应表 product_skus。
 *
 * <p>一个商品可有多个规格（如 红色/M、128G），每个规格独立定价与库存。
 */
@Data
public class ProductSku implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 规格 ID */
    private Long id;

    /** 所属商品 ID */
    private Long productId;

    /** 规格名，如 红色/M、128G */
    private String name;

    /** 规格售价 */
    private BigDecimal price;

    /** 规格库存 */
    private Integer stock;

    /** 锁定库存（下单未支付预占，阶段 6）；可卖 = stock - locked_stock */
    private Integer lockedStock;

    /** 规格图片（可选） */
    private String image;

    // ---------- 以下为非表字段（管理端库存列表 JOIN 填充） ----------

    /** 商品名称 */
    private String productName;

    /** 商品 SKU 编码 */
    private String productSku;

    /** 商品状态：1=上架，0=下架 */
    private Integer productStatus;

    /** 可卖库存（stock - locked_stock，查询时计算） */
    private Integer available;
}
