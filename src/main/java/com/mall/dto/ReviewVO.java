package com.mall.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评价展示 VO（阶段 7）：商品详情评价列表 / 管理端评价表格共用。
 *
 * <p>images 在数据库里是 JSON 字符串，出参转回 List&lt;String&gt; 方便前端直接渲染；
 * statusText 由状态码转中文（待审核/已发布/已隐藏）。
 */
@Data
public class ReviewVO {

    /** 评价主键 */
    private Long id;

    /** 订单 ID（雪花 ID，字符串输出防 JS 精度丢失，见 OrderVO.id 说明） */
    private String orderId;

    /** 订单明细 ID */
    private Long orderItemId;

    /** 评价用户 ID */
    private Long userId;

    /** 商品 ID */
    private Long productId;

    /** 规格 ID（0=无规格） */
    private Long skuId;

    /** 评分 1-5 星 */
    private Integer rating;

    /** 评价内容 */
    private String content;

    /** 评价图片（List，由 JSON 字符串反序列化） */
    private List<String> images;

    /** 状态：0=待审核 1=已发布 2=已隐藏 */
    private Integer status;

    /** 状态中文名 */
    private String statusText;

    /** 用户名（JOIN users） */
    private String username;

    /** 商品名（JOIN products，管理端表格展示） */
    private String productName;

    /** 规格名（可为空） */
    private String skuName;

    /** 商家回复 */
    private String reply;

    /** 回复时间 */
    private LocalDateTime replyAt;

    /** 评价时间 */
    private LocalDateTime createdAt;
}
