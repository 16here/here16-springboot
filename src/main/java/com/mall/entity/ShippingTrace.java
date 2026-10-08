package com.mall.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物流轨迹实体（阶段 9 物流跟踪）。
 *
 * <p>一张运单下的一段轨迹详情（如"您的包裹已由顺丰揽收" / "运输中，已到达杭州转运中心"）。
 * 由 LogisticsSyncTask 定时或管理端手动同步写入；用户端按时间正序展示时间轴。
 * 表：shipping_traces
 */
@Data
public class ShippingTrace {

    /** 轨迹主键 */
    private Long id;

    /** 所属运单 ID */
    private Long shippingId;

    /** 轨迹发生时间（展示在时间轴上） */
    private LocalDateTime trackTime;

    /** 轨迹描述（"您的包裹已由顺丰揽收"） */
    private String trackInfo;

    /** 轨迹地点（"杭州转运中心"，可空） */
    private String location;

    /** 写入时间 */
    private LocalDateTime createdAt;
}
