package com.mall.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 运单展示对象（阶段 9 物流跟踪）。
 *
 * <p>用户端查物流 / 管理端运单列表共用：运单基础信息 + 收货人摘要 + 轨迹时间轴。
 */
@Data
public class ShippingVO {

    /** 运单 ID */
    private Long id;

    /** 订单 ID（雪花 ID，字符串输出防 JS 精度丢失，见 OrderVO.id 说明） */
    private String orderId;

    /** 订单号 */
    private String orderNo;

    /** 快递公司 */
    private String shippingCompany;

    /** 快递单号 */
    private String trackingNo;

    /** 物流状态：0 已发货 / 1 运输中 / 2 已签收 */
    private Integer status;

    /** 物流状态中文（已发货/运输中/已签收） */
    private String statusText;

    /** 预计送达时间 */
    private LocalDateTime estimatedTime;

    /** 发货时间 */
    private LocalDateTime createdAt;

    /** 收货人姓名（管理端列表展示；用户端为本人订单不必敏感） */
    private String receiverName;

    /** 收货人手机号 */
    private String receiverPhone;

    /** 收货地址 */
    private String receiverAddress;

    /** 物流轨迹（时间正序，用户端时间轴直接渲染） */
    private List<TrackingVO> traces;
}
