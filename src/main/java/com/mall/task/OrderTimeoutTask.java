package com.mall.task;

import com.mall.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单超时取消定时任务。
 *
 * <p>每隔 {@code order.timeout-minutes} 分钟（默认 30 分钟）扫描一次：
 * 找出"待付款且超过超时阈值"的订单，逐单走状态机的 TIMEOUT 事件自动取消
 * （取消后恢复库存，保证库存不因占单而白白锁死——淘宝同款机制）。
 *
 * <p>设计说明：
 * <ul>
 *   <li><b>兜底而非唯一防线</b>：真实项目通常还有"支付回调时校验超时 + 定时任务"双保险，
 *       本任务作为最终兜底，把恶意占库存的单子清理掉；</li>
 *   <li><b>单笔独立事务</b>：每单在自己的事务里取消（OrderService.timeoutCancel 自带
 *       @Transactional），某一单失败不影响其余批次；</li>
 *   <li><b>静默跳过</b>：状态已变（如刚支付/刚取消）的订单直接跳过，不抛异常。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutTask {

    private final OrderService orderService;

    /** 超时阈值（分钟，application.yml 的 order.timeout-minutes，默认 30） */
    @Value("${order.timeout-minutes:30}")
    private int timeoutMinutes;

    /**
     * 每 60 秒扫描一次超时待付款订单。
     *
     * <p>fixedDelay：上一次执行结束后再等 60 秒（不重叠执行）；
     * initialDelay：应用启动 15 秒后再跑第一次（等数据库连接就绪）。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void cancelTimeoutOrders() {
        try {
            List<Long> orderIds = orderService.findTimeoutOrderIds(timeoutMinutes);
            if (orderIds.isEmpty()) {
                return;
            }
            int success = 0;
            for (Long orderId : orderIds) {
                try {
                    orderService.timeoutCancel(orderId);
                    success++;
                } catch (Exception e) {
                    // 单笔失败不影响批次；记日志便于排查
                    log.error("超时取消订单 {} 失败: {}", orderId, e.getMessage());
                }
            }
            log.info("超时订单取消任务完成: 命中 {} 单，成功 {} 单，阈值 {} 分钟",
                    orderIds.size(), success, timeoutMinutes);
        } finally {
            // Phase 3 分表：定时任务线程常驻，必须清理本任务设置的分表路由，
            // 防止串到下一次任务执行
            com.mall.common.ShardingContext.clear();
        }
    }
}
