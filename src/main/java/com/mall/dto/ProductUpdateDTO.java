package com.mall.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 更新商品请求参数（管理端编辑商品，全量更新）。
 */
@Data
public class ProductUpdateDTO {

    @NotBlank(message = "SKU 编码不能为空")
    @Size(max = 64, message = "SKU 编码过长")
    private String sku;

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 200, message = "商品名称过长")
    private String name;

    @Size(max = 5000, message = "商品描述过长")
    private String description;

    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.00", message = "价格不能为负数")
    private BigDecimal price;

    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

    @NotNull(message = "请选择分类")
    private Long categoryId;

    private Integer status = 1;

    /** 商品图片地址列表（全量替换） */
    private List<String> images;

    /** 规格列表（全量替换，见 SkuItemDTO） */
    @Valid
    private List<SkuItemDTO> skus;
}
