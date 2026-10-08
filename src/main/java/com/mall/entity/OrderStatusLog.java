package com.mall.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单状态变更日志实体，对应 order_status_log 表。
 *
 * <p>每一次订单状态流转都会写入一条日志（由 OrderService 统一记录）：
 * 用户端详情页的"状态时间轴"、管理端问题订单追溯、客服对账都依赖它。
 */
@Data
public class OrderStatusLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 日志主键 */
    private Long id;

    /** 订单 ID */
    private Long orderId;

    /** 订单号（冗余存储，便于直接按单号检索） */
    private String orderNo;

    /** 变更前状态码 */
    private Integer fromStatus;

    /** 变更后状态码 */
    private Integer toStatus;

    /** 触发事件：PAY/SHIP/CONFIRM/CANCEL/TIMEOUT/CLOSE */
    private String event;

    /** 操作方：USER=用户 ADMIN=管理员 SYSTEM=系统定时任务 */
    private String operatorType;

    /** 操作人 ID（SYSTEM 操作时为 null） */
    private Long operatorId;

    /** 备注（如取消原因、物流单号） */
    private String remark;

    /** 变更时间 */
    private LocalDateTime createdAt;

    // ---------- 以下为非表字段（服务层填充，供前端展示） ----------

    /** 变更前状态中文名（由 OrderStatusEnum 转换） */
    private String fromStatusText;

    /** 变更后状态中文名（由 OrderStatusEnum 转换） */
    private String toStatusText;
}
