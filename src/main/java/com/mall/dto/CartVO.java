package com.mall.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 购物车视图对象：明细 + 汇总。
 */
@Data
@AllArgsConstructor
public class CartVO {

    /** 购物车明细 */
    private List<CartItemVO> items;

    /** 总件数（数量求和） */
    private Integer totalCount;

    /** 总金额（数量 × 单价求和，由服务层计算） */
    private BigDecimal totalPrice;
}
