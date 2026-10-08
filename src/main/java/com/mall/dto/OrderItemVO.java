package com.mall.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单明细视图对象。
 */
@Data
public class OrderItemVO {

    /** 订单明细 ID（发表评价时回传 orderItemId；阶段 7 补充） */
    private Long orderItemId;

    /** 商品 ID */
    private Long productId;

    /** 商品名称（快照） */
    private String productName;

    /** 商品主图（快照） */
    private String productImage;

    /** 规格 ID；0=无规格 */
    private Long skuId;

    /** 规格名称（快照） */
    private String skuName;

    /** 成交单价（快照） */
    private BigDecimal price;

    /** 购买数量 */
    private Integer quantity;

    /** 小计（单价 × 数量） */
    private BigDecimal subtotal;
}
