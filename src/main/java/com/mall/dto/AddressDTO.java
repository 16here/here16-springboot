package com.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 收货地址请求参数（新增 / 编辑共用，均不允许传 userId——归属由后端取登录用户）。
 */
@Data
public class AddressDTO {

    /** 收货人姓名 */
    @NotBlank(message = "收货人姓名不能为空")
    private String receiverName;

    /** 收货人手机号 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String receiverPhone;

    /** 省 */
    @NotBlank(message = "请选择省份")
    private String province;

    /** 市 */
    @NotBlank(message = "请选择城市")
    private String city;

    /** 区/县 */
    @NotBlank(message = "请选择区/县")
    private String district;

    /** 详细地址 */
    @NotBlank(message = "详细地址不能为空")
    private String detail;

    /** 是否设为默认地址（可选，默认 false） */
    private Boolean isDefault;
}
