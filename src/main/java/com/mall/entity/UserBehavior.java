package com.mall.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户行为实体（阶段 10：推荐系统）。
 *
 * <p>对应 user_behaviors 表。一条记录 = 一次行为（浏览 / 收藏 / 加购 / 购买），
 * 是推荐系统的"燃料"：热度推荐按行为类型权重计分，
 * 物品协同过滤按"同一批用户行为过的商品共现"算相似度。
 */
@Data
public class UserBehavior {

    /** 行为主键 */
    private Long id;

    /** 用户 ID（NULL = 游客行为，仅参与热度统计，不参与个性化推荐） */
    private Long userId;

    /** 商品 ID */
    private Long productId;

    /** SKU ID（可空：无规格商品或浏览行为不涉及具体规格） */
    private Long skuId;

    /** 行为类型：VIEW 浏览 / FAVORITE 收藏 / CART 加购 / PURCHASE 购买 */
    private String behaviorType;

    /** 行为发生时间（数据库默认当前时间） */
    private LocalDateTime createdAt;
}
