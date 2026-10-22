package com.mall.task;

import com.mall.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 对账定时任务（Phase 3 对账体系）。
 *
 * <p>每天凌晨 02:00 自动执行一次全量对账（核对<b>昨天</b>支付成功的流水
 * 与订单、库存三方一致性），发现的问题写入 reconciliation_logs，
 * 管理端第二天上班就能看到并处理。
 *
 * <p>为什么用 cron 而不是 fixedDelay：对账是"按天"的业务动作，
 * 必须固定在每天同一时刻执行（凌晨业务低谷，对账查询不干扰在线交易）；
 * 配合管理端手动触发（POST /api/admin/reconciliation/run）双通道。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationTask {

    private final ReconciliationService reconciliationService;

    /** 每天 02:00 对账昨天（cron：秒 分 时 日 月 周） */
    @Scheduled(cron = "0 0 2 * * ?")
    public void dailyReconcile() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("对账任务开始: bizDate={}", yesterday);
        try {
            var vo = reconciliationService.run(yesterday);
            log.info("对账任务完成: bizDate={}, 已支付流水={}, 通过={}, 问题={}",
                    vo.getBizDate(), vo.getTotalPaid(), vo.getChecked(), vo.getIssueCount());
        } catch (Exception e) {
            // 对账失败不能影响其他业务；记日志，管理端可手动重跑
            log.error("对账任务失败: bizDate={}, err={}", yesterday, e.getMessage(), e);
        }
    }
}
