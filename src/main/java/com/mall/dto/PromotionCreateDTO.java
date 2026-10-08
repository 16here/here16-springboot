package com.mall.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端创建/编辑营销活动的入参（阶段 8）。
 *
 * <p>type 决定必填项：
 * <ul>
 *   <li>type=1（满减）：threshold + amount；</li>
 *   <li>type=2（折扣）：discountRate（0~1，如 0.85=8.5 折）；</li>
 *   <li>type=3（秒杀）：rules 规则列表（商品 + 规格 + 秒杀价）。</li>
 * </ul>
 */
@Data
public class PromotionCreateDTO {

    /** 活动名称 */
    @NotBlank(message = "请输入活动名称")
    private String name;

    /** 类型：1=满减 2=折扣 3=秒杀 */
    @NotNull(message = "请选择活动类型")
    private Integer type;

    /** 满减门槛（type=1） */
    @DecimalMin(value = "0.01", message = "满减门槛必须大于 0")
    private BigDecimal threshold;

    /** 满减金额（type=1） */
    @DecimalMin(value = "0.01", message = "满减金额必须大于 0")
    private BigDecimal amount;

    /** 折扣率（type=2，如 0.85=8.5 折） */
    private BigDecimal discountRate;

    /** 秒杀规则（type=3）：商品 + 规格（可选）+ 秒杀价 */
    private List<com.mall.entity.Promotion.FlashSaleRule> rules;

    /** 活动开始时间 */
    @NotNull(message = "请选择活动开始时间")
    private LocalDateTime startTime;

    /** 活动结束时间 */
    @NotNull(message = "请选择活动结束时间")
    private LocalDateTime endTime;

    /** 状态：1=启用 0=停用 */
    @NotNull(message = "请选择状态")
    private Integer status;
}
