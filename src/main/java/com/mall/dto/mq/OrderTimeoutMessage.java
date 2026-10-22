package com.mall.dto.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单超时检查消息体（Phase 2 延迟消息）。
 *
 * <p>下单成功后立即发送一条"延迟 30 分钟"的消息；消费者在延迟到点后收到它，
 * 检查该订单是否仍是"待付款"，是则走状态机 TIMEOUT 自动取消并恢复库存。
 * 相比"每分钟扫一次全表"的定时任务，延迟消息是"按单精准触发"，延迟小、无空扫。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderTimeoutMessage {

    /** 订单 ID */
    private Long orderId;

    /** 订单号（日志排查用） */
    private String orderNo;
}
