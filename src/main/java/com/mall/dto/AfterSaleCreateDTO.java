package com.mall.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 申请售后请求体（阶段 7）。
 *
 * <p>只传"订单 + 类型 + 原因 + 退款金额"，订单归属、订单状态（待发货/待收货/已完成
 * 才可申请）、金额上限（≤ 订单实付）由 {@link com.mall.service.AfterSaleService}
 * 从数据库校验，防止用户伪造退款金额。
 */
@Data
public class AfterSaleCreateDTO {

    /** 订单 ID（必须属于当前用户且状态可售后） */
    @NotNull(message = "订单不能为空")
    private Long orderId;

    /** 售后类型：1=仅退款 2=退货退款 */
    @NotNull(message = "请选择售后类型")
    @Min(value = 1, message = "售后类型不合法")
    @Max(value = 2, message = "售后类型不合法")
    private Integer type;

    /** 申请原因（非空） */
    @NotBlank(message = "请填写申请原因")
    private String reason;

    /** 退款金额（≤ 订单实付金额，校验在 Service） */
    @NotNull(message = "请填写退款金额")
    @DecimalMin(value = "0.01", message = "退款金额必须大于 0")
    private BigDecimal amount;
}
