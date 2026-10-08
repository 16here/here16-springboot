package com.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发货请求参数（阶段 9 物流跟踪）。
 *
 * <p>管理端发货弹窗提交：选择订单 + 快递公司 + 快递单号。
 * 校验规则写在 DTO 上（@NotNull / @NotBlank），Spring 自动完成参数校验，
 * 校验失败由全局异常处理器统一返回 400。
 */
@Data
public class ShippingCreateDTO {

    /** 待发货订单 ID */
    @NotNull(message = "订单不能为空")
    private Long orderId;

    /** 快递公司（顺丰/中通/圆通/韵达/申通/京东/邮政EMS） */
    @NotBlank(message = "请选择快递公司")
    private String shippingCompany;

    /** 快递单号 */
    @NotBlank(message = "请填写快递单号")
    private String trackingNo;
}
