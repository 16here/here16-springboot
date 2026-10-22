package com.mall.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 售后单展示 VO（阶段 7）：用户售后列表 / 管理端售后表格共用。
 *
 * <p>typeText / statusText 由状态码转中文；订单侧信息（订单状态、订单总金额、
 * 下单用户名）由 JOIN 带出，方便用户与运营查看"退的是哪一单"。
 */
@Data
public class AfterSaleVO {

    /** 售后单主键 */
    private Long id;

    /** 售后单号 */
    private String afterSaleNo;

    /** 订单 ID（雪花 ID，字符串输出防 JS 精度丢失，见 OrderVO.id 说明） */
    private String orderId;

    /** 订单号 */
    private String orderNo;

    /** 申请用户 ID */
    private Long userId;

    /** 用户名（管理端展示） */
    private String username;

    /** 类型：1=仅退款 2=退货退款 */
    private Integer type;

    /** 类型中文名 */
    private String typeText;

    /** 申请原因 */
    private String reason;

    /** 退款金额 */
    private BigDecimal amount;

    /** 状态：0=待审核 1=待寄回 2=已完成 3=已拒绝 4=已撤销 */
    private Integer status;

    /** 状态中文名 */
    private String statusText;

    /** 退货物流单号 */
    private String logisticsNo;

    /** 审核意见 */
    private String auditRemark;

    /** 申请时间 */
    private LocalDateTime applyAt;

    /** 审核时间 */
    private LocalDateTime auditAt;

    /** 退款完成时间 */
    private LocalDateTime refundAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 订单状态（快照展示：0待付款…4已取消） */
    private Integer orderStatus;

    /** 订单状态中文名 */
    private String orderStatusText;

    /** 订单总金额 */
    private BigDecimal orderTotalAmount;

    /** 订单商品明细（详情接口附带，用户/管理端查看"退的是哪些商品"） */
    private List<OrderItemVO> items;
}
