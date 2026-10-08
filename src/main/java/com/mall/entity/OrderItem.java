package com.mall.entity;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单明细实体，对应 order_items 表。
 *
 * <p>下单时把商品名称/主图/规格名/成交单价【快照】进来，
 * 商品后续改名、改价、删除都不影响已成交订单的展示。
 */
@Data
public class OrderItem {

    /** 明细主键 */
    private Long id;

    /** 所属订单 ID */
    private Long orderId;

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
}
