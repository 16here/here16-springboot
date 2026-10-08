package com.mall.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 营销活动实体，对应 promotions 表（阶段 8）。
 *
 * <p>活动是"自动参与"的营销工具（无需领券，下单时自动计算优惠）：
 * <ul>
 *   <li>type=1 满减：订单商品金额满 threshold 减 amount（全场适用）；</li>
 *   <li>type=2 折扣：订单商品金额按 discountRate 打折（如 0.85=8.5 折）；</li>
 *   <li>type=3 秒杀：rules 指定商品/SKU 的秒杀价（下单时命中即按秒杀价成交）。</li>
 * </ul>
 *
 * <p>同时存在多个满减/折扣活动时取"优惠最大"的一个；秒杀独立按商品命中，
 * 秒杀优惠与满减/折扣优惠可以叠加（满减/折扣基于非秒杀商品金额计算）。
 */
@Data
public class Promotion {

    /** 活动主键 */
    private Long id;

    /** 活动名称（如"双十一全场满 300 减 50""新品 8.5 折""限时秒杀"） */
    private String name;

    /** 类型：1=满减 2=折扣 3=秒杀 */
    private Integer type;

    /** 满减门槛（type=1，订单商品金额满 threshold 可用） */
    private BigDecimal threshold;

    /** 满减金额（type=1） */
    private BigDecimal amount;

    /** 折扣率（type=2，如 0.85） */
    private BigDecimal discountRate;

    /** 秒杀规则 JSON（type=3）：[{productId, skuId, price}]，Service 层轻量解析 */
    private String rules;

    /** 活动开始时间 */
    private LocalDateTime startTime;

    /** 活动结束时间 */
    private LocalDateTime endTime;

    /** 状态：1=启用 0=停用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    // ---------- 以下为非表字段（展示用） ----------

    /** 类型中文名（满减/折扣/秒杀），Service 填充 */
    private String typeText;

    /** 状态中文名（启用/停用），Service 填充 */
    private String statusText;

    /** 秒杀规则对象列表（Service 从 rules JSON 解析，管理端展示用） */
    private java.util.List<FlashSaleRule> ruleItems;

    /** 秒杀规则内部类：一个秒杀条目 = 商品 + 规格（可选）+ 秒杀价 */
    @lombok.Data
    public static class FlashSaleRule {
        private Long productId;
        private Long skuId;
        private BigDecimal price;

        public FlashSaleRule() {
        }

        public FlashSaleRule(Long productId, Long skuId, BigDecimal price) {
            this.productId = productId;
            this.skuId = skuId;
            this.price = price;
        }
    }
}
