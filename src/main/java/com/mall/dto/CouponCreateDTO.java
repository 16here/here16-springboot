package com.mall.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 管理端创建/编辑优惠券模板的入参。
 *
 * <p>type 决定必填项：
 * <ul>
 *   <li>type=1（满减券）：amount 必填，实付 = 订单金额 - amount；</li>
 *   <li>type=2（折扣券）：discountRate 必填，实付 = 订单金额 × discountRate。</li>
 * </ul>
 * 两类券都有 threshold 门槛（满 X 元可用，0=无门槛）。
 */
@Data
public class CouponCreateDTO {

    /** 券名称 */
    @NotBlank(message = "请输入优惠券名称")
    private String name;

    /** 类型：1=满减券 2=折扣券 */
    @NotNull(message = "请选择优惠券类型")
    private Integer type;

    /** 使用门槛（满 X 元可用，0=无门槛） */
    @NotNull(message = "请设置使用门槛")
    @DecimalMin(value = "0", message = "门槛不能为负数")
    private BigDecimal threshold;

    /** 减免金额（满减券必填） */
    @DecimalMin(value = "0.01", message = "减免金额必须大于 0")
    private BigDecimal amount;

    /** 折扣率（折扣券必填，如 0.90=9 折） */
    @DecimalMin(value = "0.01", message = "折扣率必须大于 0")
    private BigDecimal discountRate;

    /** 发行总量（0=不限量） */
    @Min(value = 0, message = "发行总量不能为负数")
    private Integer totalQuantity;

    /** 每人限领（默认 1） */
    @Min(value = 1, message = "每人至少可领 1 张")
    private Integer perUserLimit;

    /** 状态：1=上架 0=下架 */
    @NotNull(message = "请选择状态")
    private Integer status;

    /** 有效期开始 */
    @NotNull(message = "请选择有效期")
    private LocalDateTime validFrom;

    /** 有效期结束 */
    @NotNull(message = "请选择有效期")
    private LocalDateTime validUntil;

    /** 类型/字段联动校验：满减券必须有 amount、折扣券必须有 discountRate、有效期必须先后 */
    @jakarta.validation.constraints.AssertTrue(message = "满减券必须填写减免金额，折扣券必须填写折扣率，且结束时间要晚于开始时间")
    public boolean isRuleValid() {
        if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
            return false;
        }
        if (type == null) {
            return true; // type 为空交给 @NotNull 报错
        }
        if (type == 1) {
            return amount != null;
        }
        if (type == 2) {
            return discountRate != null;
        }
        return false;
    }
}
