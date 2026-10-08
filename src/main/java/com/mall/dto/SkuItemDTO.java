package com.mall.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品规格条目参数（创建/更新商品共用）。
 */
@Data
public class SkuItemDTO {

    /** 规格名，如 红色/M、128G */
    @NotBlank(message = "规格名不能为空")
    @Size(max = 100, message = "规格名过长")
    private String name;

    /** 规格售价 */
    @NotNull(message = "规格价格不能为空")
    @DecimalMin(value = "0.00", message = "规格价格不能为负数")
    private BigDecimal price;

    /** 规格库存 */
    @NotNull(message = "规格库存不能为空")
    @Min(value = 0, message = "规格库存不能为负数")
    private Integer stock;

    /** 规格图片（可选） */
    private String image;
}
