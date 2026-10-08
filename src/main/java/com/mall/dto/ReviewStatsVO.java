package com.mall.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 商品评分统计 VO（阶段 7）：商品详情页"评分概览"。
 *
 * <p>统计口径：只统计<b>已发布（status=1）</b>的评价；
 * 星级分布（five~one）由 Service 从分组查询填充，平均分保留两位小数。
 */
@Data
public class ReviewStatsVO {

    /** 商品 ID */
    private Long productId;

    /** 平均分（0-5，两位小数） */
    private BigDecimal avgRating;

    /** 评价总数 */
    private long totalCount;

    /** 5 星数量 */
    private long five;

    /** 4 星数量 */
    private long four;

    /** 3 星数量 */
    private long three;

    /** 2 星数量 */
    private long two;

    /** 1 星数量 */
    private long one;

    /** 5 星占比（百分比，前端画分布条用） */
    private int fivePercent;

    public ReviewStatsVO(Long productId) {
        this.productId = productId;
        this.avgRating = BigDecimal.ZERO;
    }

    /** 无评价时调用，给出默认统计 */
    public void empty() {
        this.avgRating = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.totalCount = 0;
    }

    /** 有评价时由 Service 调用：算平均分与 5 星占比 */
    public void finish() {
        this.avgRating = avgRating.setScale(2, RoundingMode.HALF_UP);
        this.fivePercent = totalCount == 0 ? 0 : (int) Math.round(five * 100.0 / totalCount);
    }
}
