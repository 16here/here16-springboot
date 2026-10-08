package com.mall.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体，对应表 products。
 */
@Data
public class Product implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品 ID */
    private Long id;

    /** 商品 SKU 编码（唯一） */
    private String sku;

    /** 商品名称 */
    private String name;

    /** 商品描述 */
    private String description;

    /** 售价（Decimal 保证金额精度） */
    private BigDecimal price;

    /** 库存 */
    private Integer stock;

    /** 锁定库存（下单未支付预占，阶段 6）；可卖 = stock - locked_stock */
    private Integer lockedStock;

    /** 可卖库存（stock - locked_stock，查询时计算，非表字段） */
    private Integer available;

    /** 所属分类 ID */
    private Long categoryId;

    /** 分类名称（非表字段，列表查询 LEFT JOIN 时填充） */
    private String categoryName;

    /** 状态：1=上架，0=下架 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
