package com.mall.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 商品上下架请求参数。
 */
@Data
public class StatusUpdateDTO {

    /** 目标状态：1=上架，0=下架 */
    @Min(value = 0, message = "状态值不合法")
    @Max(value = 1, message = "状态值不合法")
    private Integer status;
}
