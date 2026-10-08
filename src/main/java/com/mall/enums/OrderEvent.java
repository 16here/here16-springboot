package com.mall.enums;

/**
 * 订单状态流转事件（状态机的"输入"）。
 *
 * <p>每个事件对应一条业务动作：支付、发货、确认收货、取消、超时、管理端关闭。
 * 合法转移规则见 {@code OrderStateMachine}。
 *
 * <p>{@link #CLOSE} 为阶段 5 清单外补充：对应管理端接口
 * {@code POST /api/admin/orders/{id}/close}（关闭待付款订单），
 * 语义上是"管理端强制取消"，与用户取消/超时取消殊途同归（都到已取消）。
 */
public enum OrderEvent {

    /** 支付成功（由支付回调触发） */
    PAY,

    /** 商家发货（由管理端触发，可携带物流单号） */
    SHIP,

    /** 买家确认收货 */
    CONFIRM,

    /** 用户取消订单 */
    CANCEL,

    /** 超时未支付，系统自动取消（定时任务触发） */
    TIMEOUT,

    /** 管理端关闭订单（仅待付款可关，强制取消） */
    CLOSE
}
