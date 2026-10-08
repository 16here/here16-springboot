package com.mall.dto;

import com.mall.entity.Promotion;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 营销活动展示 VO（管理端活动管理列表/详情）。
 */
@Data
public class PromotionVO {

    /** 活动主键 */
    private Long id;

    /** 活动名称 */
    private String name;

    /** 类型：1=满减 2=折扣 3=秒杀 */
    private Integer type;

    /** 类型中文名 */
    private String typeText;

    /** 满减门槛（type=1） */
    private BigDecimal threshold;

    /** 满减金额（type=1） */
    private BigDecimal amount;

    /** 折扣率（type=2） */
    private BigDecimal discountRate;

    /** 活动开始时间 */
    private LocalDateTime startTime;

    /** 活动结束时间 */
    private LocalDateTime endTime;

    /** 状态：1=启用 0=停用 */
    private Integer status;

    /** 状态中文名 */
    private String statusText;

    /** 秒杀规则对象列表（type=3，展示用） */
    private List<Promotion.FlashSaleRule> ruleItems;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
