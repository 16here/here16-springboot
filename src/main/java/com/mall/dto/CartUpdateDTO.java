package com.mall.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 更新购物车数量请求参数。
 */
@Data
public class CartUpdateDTO {

    /** 目标数量 */
    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量不能小于 1")
    @Max(value = 99, message = "单个商品最多购买 99 件")
    private Integer quantity;
}
