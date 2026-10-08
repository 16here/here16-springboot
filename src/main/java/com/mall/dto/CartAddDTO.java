package com.mall.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 添加购物车请求参数。
 */
@Data
public class CartAddDTO {

    /** 商品 ID（必填） */
    @NotNull(message = "商品 ID 不能为空")
    private Long productId;

    /** 规格 ID；不传或 0 表示未选规格（无规格商品） */
    private Long skuId;

    /** 数量，默认 1，上限 99 */
    @Min(value = 1, message = "数量不能小于 1")
    @Max(value = 99, message = "单个商品最多购买 99 件")
    private Integer quantity = 1;
}
