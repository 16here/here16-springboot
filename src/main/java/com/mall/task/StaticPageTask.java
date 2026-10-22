package com.mall.task;

import com.mall.service.StaticPageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 静态页定时生成任务（Phase 1 静态化）。
 *
 * <p>每 5 分钟把首页商品列表重新渲染一次并落盘，
 * 保证 Nginx/CDN 托管的静态文件不会长期陈旧
 * （新上架商品最迟 5 分钟出现在静态首页）。
 *
 * <p>为什么用定时而非实时更新：首页快照是"近似新鲜"即可的数据，
 * 实时更新会引入写链路复杂度；5 分钟的陈旧度对首页展示完全可接受，
 * 与电商大促"静态首页 + 定时刷新"的通用做法一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaticPageTask {

    private final StaticPageService staticPageService;

    /** 每 5 分钟执行一次（fixedDelay：上次执行结束后再等 5 分钟，避免任务堆积） */
    @Scheduled(fixedDelay = 300_000, initialDelay = 15_000)
    public void refreshHome() {
        staticPageService.generateHomeJson();
    }
}
