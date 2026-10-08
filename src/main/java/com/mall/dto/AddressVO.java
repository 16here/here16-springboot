package com.mall.dto;

import lombok.Data;

/**
 * 收货地址视图对象（返回给前端）。
 */
@Data
public class AddressVO {

    /** 地址主键 */
    private Long id;

    /** 收货人姓名 */
    private String receiverName;

    /** 收货人手机号 */
    private String receiverPhone;

    /** 省 */
    private String province;

    /** 市 */
    private String city;

    /** 区/县 */
    private String district;

    /** 详细地址 */
    private String detail;

    /** 是否默认：1=是 0=否 */
    private Integer isDefault;

    /** 完整地址（省+市+区+详细，前端展示用） */
    private String fullAddress;
}
