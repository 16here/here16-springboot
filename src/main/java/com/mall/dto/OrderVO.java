package com.mall.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单视图对象（列表卡片与详情页共用）。
 *
 * <p>列表接口不填 items（避免 N+1 且卡片不需要明细）；
 * 详情接口填充 items。
 */
@Data
public class OrderVO {

    /** 订单主键 */
    private Long id;

    /** 订单号 */
    private String orderNo;

    /** 下单用户 ID */
    private Long userId;

    /** 下单人用户名（管理端显示） */
    private String username;

    /** 订单总金额（应付金额） */
    private BigDecimal totalAmount;

    /** 商品原价合计（优惠前，阶段 8） */
    private BigDecimal originalAmount;

    /** 活动优惠金额（满减/折扣/秒杀，阶段 8） */
    private BigDecimal promotionAmount;

    /** 优惠券减免金额（阶段 8） */
    private BigDecimal couponAmount;

    /** 订单状态：0待付款 1待发货 2待收货 3已完成 4已取消 */
    private Integer status;

    /** 状态中文名（前端展示；0 待付款 / 1 待发货 / 2 待收货 / 3 已完成 / 4 已取消） */
    private String statusText;

    /** 收货人姓名（地址快照） */
    private String receiverName;

    /** 收货电话（地址快照） */
    private String receiverPhone;

    /** 完整收货地址（地址快照） */
    private String receiverAddress;

    /** 买家备注 */
    private String remark;

    /** 物流单号（发货后填写，前端可展示） */
    private String shippingNo;

    /** 下单时间 */
    private LocalDateTime createdAt;

    /** 付款时间 */
    private LocalDateTime paidAt;

    /** 发货时间 */
    private LocalDateTime shippedAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

    /** 取消时间 */
    private LocalDateTime cancelledAt;

    /** 订单明细（详情接口填充） */
    private List<OrderItemVO> items;

    /** 状态变更日志（阶段 5：详情接口填充，供前端"状态时间轴"渲染） */
    private List<com.mall.entity.OrderStatusLog> statusLogs;
}
