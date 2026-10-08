package com.mall.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收货地址实体，对应 addresses 表。
 */
@Data
public class Address {

    /** 地址主键 */
    private Long id;

    /** 所属用户 ID */
    private Long userId;

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

    /** 是否默认地址：1=是，0=否 */
    private Integer isDefault;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
