package com.mall.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 库存列表视图对象（管理端"库存管理"页）。
 *
 * <p>一行代表一个"可调整库存的最小粒度"：
 * <ul>
 *   <li>有规格商品：每个规格一行（skuId &gt; 0）；</li>
 *   <li>无规格商品：商品本身一行（skuId = 0，规格名显示"默认"）。</li>
 * </ul>
 */
@Data
public class InventorySkuVO {

    /** 规格 ID（0=商品级） */
    private Long skuId;

    /** 商品 ID */
    private Long productId;

    /** 商品名称 */
    private String productName;

    /** 商品 SKU 编码 */
    private String productSku;

    /** 规格名称（商品级显示"默认"） */
    private String skuName;

    /** 售价（规格价或商品价） */
    private BigDecimal price;

    /** 总库存 stock */
    private Integer stock;

    /** 锁定库存 locked_stock（下单未支付预占） */
    private Integer lockedStock;

    /** 可卖库存 available = stock - locked_stock */
    private Integer available;

    /** 商品状态：1=上架，0=下架 */
    private Integer status;
}
