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
 * 创建商品请求参数（管理端新增商品）。
 *
 * <p>图片与规格为可选项：图片地址列表、规格列表；规格列表校验放在
 * 内嵌对象（{@link SkuItemDTO}）上，@Valid 触发级联校验。
 */
@Data
public class ProductCreateDTO {

    /** 商品 SKU 编码（唯一） */
    @NotBlank(message = "SKU 编码不能为空")
    @Size(max = 64, message = "SKU 编码过长")
    private String sku;

    /** 商品名称 */
    @NotBlank(message = "商品名称不能为空")
    @Size(max = 200, message = "商品名称过长")
    private String name;

    /** 商品描述 */
    @Size(max = 5000, message = "商品描述过长")
    private String description;

    /** 售价 */
    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.00", message = "价格不能为负数")
    private BigDecimal price;

    /** 库存 */
    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

    /** 所属分类 ID */
    @NotNull(message = "请选择分类")
    private Long categoryId;

    /** 状态：1=上架，0=下架，默认上架 */
    private Integer status = 1;

    /** 商品图片地址列表 */
    private List<String> images;

    /** 规格列表（见 SkuItemDTO） */
    @Valid
    private List<SkuItemDTO> skus;
}
