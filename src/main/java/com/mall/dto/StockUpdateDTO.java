package com.mall.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 库存调整入参（管理端"调整库存"）。
 *
 * <p>设计：只允许后端"绝对值调整"（delta 可正可负），
 * 不允许前端直接设置库存绝对值——避免并发下覆盖别人刚写入的值；
 * 减库存时后端用条件 UPDATE 保证不为负。
 */
@Data
public class StockUpdateDTO {

    /** 规格 ID；0 表示商品级（无规格商品，需配合 productId） */
    @NotNull(message = "规格 ID 不能为空")
    private Long skuId;

    /** 商品 ID（skuId=0 时必填，用于定位商品级库存） */
    private Long productId;

    /** 调整数量：正=补货，负=减少（不可为 0） */
    @NotNull(message = "调整数量不能为空")
    private Integer delta;

    /** 调整原因（必填，便于流水追溯） */
    private String remark;
}
