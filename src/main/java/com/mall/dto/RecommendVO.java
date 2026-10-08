package com.mall.dto;

import lombok.Data;

import java.util.List;

/**
 * 推荐结果 VO（阶段 10）。
 *
 * <p>一个推荐位 = 标题 + 推荐理由 + 商品列表。
 * 前端 RecommendList 组件直接渲染：标题告诉用户"这是什么推荐位"，
 * 理由告诉用户"为什么推这些"（提升推荐可信度，淘宝同款交互）。
 */
@Data
public class RecommendVO {

    /** 推荐位标题（如"猜你喜欢 / 热门推荐 / 相关推荐"） */
    private String title;

    /** 推荐理由文案（如"根据您的浏览记录推荐 / 看过此商品的用户还看了"） */
    private String reason;

    /** 推荐商品列表（复用商品列表 VO：id/name/price/cover/分类等） */
    private List<ProductListVO> products;
}
