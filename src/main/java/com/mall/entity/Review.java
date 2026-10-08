package com.mall.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品评价实体，对应 reviews 表（阶段 7）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>评价挂在<b>订单明细</b>上（order_item_id 唯一）：一个订单对同一商品只能评价一次，
 *       一个订单可对多个商品分别评价；</li>
 *   <li>默认 <b>status=0 待审核</b>，管理端审核通过（1）后才在商品详情公开展示——
 *       评价审核是需求明确的管控动作；</li>
 *   <li>商家可回复（reply + reply_at），买家评价列表直接展示回复。</li>
 * </ul>
 */
@Data
public class Review {

    /** 评价主键 */
    private Long id;

    /** 订单 ID */
    private Long orderId;

    /** 订单明细 ID（唯一：一个明细一条评价） */
    private Long orderItemId;

    /** 评价用户 ID */
    private Long userId;

    /** 商品 ID */
    private Long productId;

    /** 规格 ID；0=无规格 */
    private Long skuId;

    /** 评分 1-5 星 */
    private Integer rating;

    /** 评价内容 */
    private String content;

    /** 评价图片（JSON 数组字符串，如 ["url1","url2"]） */
    private String images;

    /** 状态：0=待审核 1=已发布 2=已隐藏 */
    private Integer status;

    /** 商家回复 */
    private String reply;

    /** 回复时间 */
    private LocalDateTime replyAt;

    /** 评价时间 */
    private LocalDateTime createdAt;

    // ---------- 非表字段（JOIN 查询/展示用，不落库） ----------

    /** 用户名（JOIN users） */
    private String username;

    /** 商品名（JOIN products） */
    private String productName;

    /** 规格名（JOIN product_skus，可为空） */
    private String skuName;
}
