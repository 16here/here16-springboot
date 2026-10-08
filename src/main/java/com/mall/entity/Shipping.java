package com.mall.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运单实体（阶段 9 物流跟踪）。
 *
 * <p>一个订单一张运单（shipping.order_id 建唯一键）：管理端发货时创建，
 * 记录快递公司与快递单号；物流状态随轨迹同步推进（0 已发货 → 1 运输中 → 2 已签收）。
 * 表：shipping
 */
@Data
public class Shipping {

    /** 运单主键 */
    private Long id;

    /** 订单 ID（一个订单一张运单） */
    private Long orderId;

    /** 订单号（冗余存储，列表展示免 JOIN） */
    private String orderNo;

    /** 快递公司（顺丰/中通/圆通/韵达/申通/京东/邮政EMS） */
    private String shippingCompany;

    /** 快递单号 */
    private String trackingNo;

    /** 物流状态：0 已发货 / 1 运输中 / 2 已签收 */
    private Integer status;

    /** 预计送达时间（可空） */
    private LocalDateTime estimatedTime;

    /** 发货时间 */
    private LocalDateTime createdAt;

    /** 更新时间（轨迹同步时自动刷新） */
    private LocalDateTime updatedAt;

    // ---------- 非表字段：管理端列表展示用 ----------

    /** 物流状态中文（Service 填充：已发货/运输中/已签收） */
    private String statusText;

    /** 收货人姓名（JOIN orders 带出，管理端运单列表展示） */
    private String receiverName;

    /** 收货人手机号（JOIN orders 带出） */
    private String receiverPhone;

    /** 收货地址（JOIN orders 带出） */
    private String receiverAddress;
}
