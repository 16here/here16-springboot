package com.mall.enums;

/**
 * 订单状态枚举。
 *
 * <p>对应 orders.status 字段（0~4，与阶段 3 起的数据约定一致，未破坏存量数据）。
 *
 * <p>需求中的"已付款 / 待发货"对应同一状态 {@link #PAID}（支付成功后、商家发货前），
 * "已发货 / 待收货"对应同一状态 {@link #SHIPPED}（发货后、买家收货前）——
 * 一个状态在不同视角下有两种叫法，此处以"买家视角"命名并加注释说明。
 *
 * <p>状态流转只允许通过 {@code OrderStateMachine}（事件驱动），
 * 不允许业务代码直接改状态数字。
 */
public enum OrderStatusEnum {

    /** 0：待付款 —— 下单成功、尚未支付 */
    UNPAID(0, "待付款"),

    /** 1：待发货 —— 支付成功（= 需求中的"已付款"），等待商家发货 */
    PAID(1, "待发货"),

    /** 2：待收货 —— 商家已发货（= 需求中的"已发货"），等待买家确认收货 */
    SHIPPED(2, "待收货"),

    /** 3：已完成 —— 买家确认收货，交易闭环 */
    COMPLETED(3, "已完成"),

    /** 4：已取消 —— 用户取消 / 超时未支付 / 管理端关闭 */
    CANCELLED(4, "已取消");

    private final int code;
    private final String text;

    OrderStatusEnum(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public int getCode() {
        return code;
    }

    public String getText() {
        return text;
    }

    /** 按状态码取枚举；未知状态返回 null（调用方自行处理） */
    public static OrderStatusEnum fromCode(int code) {
        for (OrderStatusEnum status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }

    /** 按状态码取中文名（未知返回"未知"） */
    public static String textOf(int code) {
        OrderStatusEnum status = fromCode(code);
        return status == null ? "未知" : status.getText();
    }
}
