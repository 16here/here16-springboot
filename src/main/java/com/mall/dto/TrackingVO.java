package com.mall.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物流轨迹展示对象（阶段 9 物流跟踪）。
 *
 * <p>时间轴上的一个节点：发生时间 + 描述 + 地点。
 */
@Data
public class TrackingVO {

    /** 轨迹主键 */
    private Long id;

    /** 轨迹发生时间 */
    private LocalDateTime trackTime;

    /** 轨迹描述（"您的包裹已由顺丰揽收"） */
    private String trackInfo;

    /** 轨迹地点（可空） */
    private String location;
}
