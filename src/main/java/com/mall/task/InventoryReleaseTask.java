package com.mall.task;

import com.mall.service.InventoryService;
import com.mall.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 库存兜底释放任务（阶段 6，每分钟扫描一次）。
 *
 * <p>职责：<b>双保险</b>——即使 {@code OrderTimeoutTask} 因异常跳过、或某笔订单
 * 状态异常导致锁定未释放，也要保证"超时未支付订单"预占的库存最终回到可卖池。
 *
 * <p>幂等设计：{@link InventoryService#releaseIfLocked} 按订单检查——
 * 已有 RELEASE 流水（已释放过）或从未 LOCK（没锁过）则跳过，
 * 与 OrderTimeoutTask 并发执行也不会重复释放、不会虚增库存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryReleaseTask {

    private final OrderService orderService;
    private final InventoryService inventoryService;

    /** 超时阈值（分钟），与 OrderTimeoutTask 保持一致（读自 application.yml） */
    @Value("${order.timeout-minutes:30}")
    private int timeoutMinutes;

    /**
     * 每分钟扫描：找出超时未支付订单 → 逐单兜底释放锁定库存。
     */
    @Scheduled(cron = "0 * * * * *")
    public void releaseTimeoutLockedStock() {
        List<Long> timeoutIds = orderService.findTimeoutOrderIds(timeoutMinutes);
        if (timeoutIds.isEmpty()) {
            return;
        }
        log.info("库存兜底释放任务：发现超时未支付订单 {} 笔，开始核对", timeoutIds.size());
        for (Long orderId : timeoutIds) {
            try {
                inventoryService.releaseIfLocked(orderId);
            } catch (Exception e) {
                // 单笔失败不影响其他订单
                log.error("库存兜底释放失败: orderId={}, err={}", orderId, e.getMessage());
            }
        }
    }
}
