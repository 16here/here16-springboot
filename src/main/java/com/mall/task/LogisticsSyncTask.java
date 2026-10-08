package com.mall.task;

import com.mall.entity.Shipping;
import com.mall.repository.ShippingRepository;
import com.mall.service.ShippingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 物流轨迹同步定时任务（阶段 9）。
 *
 * <p>每隔 5 分钟扫描一次"未签收"的运单（shipping.status &lt; 2），
 * 逐单调用 ShippingService.syncTrace 同步最新物流轨迹。
 *
 * <p>设计说明：
 * <ul>
 *   <li><b>供应商可切换</b>：kuaidi.provider=mock 时本地模拟推进（演示闭环）；
 *       配置 kuaidi100/kuaidibird 密钥后自动切换真实查询；</li>
 *   <li><b>单笔独立事务</b>：syncTrace 自带 @Transactional，某一单失败不影响其余批次；</li>
 *   <li><b>天然幂等</b>：真实轨迹"清旧 + 全量覆盖"，mock 轨迹按节点数推进，
 *       重复轮询不会产生重复节点；已签收（status=2）运单自动跳过。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogisticsSyncTask {

    /** 单批次最多处理的运单数（防止库存过大时单次轮询过久） */
    private static final int BATCH_LIMIT = 100;

    private final ShippingRepository shippingRepository;
    private final ShippingService shippingService;

    /**
     * 每 5 分钟同步一次未签收运单的轨迹。
     *
     * <p>fixedDelay：上一次执行结束后再等 5 分钟（不重叠执行）；
     * initialDelay：应用启动 30 秒后再跑第一次（等数据库与外部服务就绪）。
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 30_000)
    public void syncLogistics() {
        List<Shipping> pending = shippingRepository.selectPendingSync(BATCH_LIMIT);
        if (pending.isEmpty()) {
            return;
        }
        int success = 0;
        for (Shipping shipping : pending) {
            try {
                shippingService.syncTrace(shipping.getId());
                success++;
            } catch (Exception e) {
                // 单笔失败不影响批次；记日志便于排查
                log.error("同步物流轨迹失败 shippingId={}: {}", shipping.getId(), e.getMessage());
            }
        }
        log.info("物流轨迹同步任务完成: 处理 {} 单，成功 {} 单", pending.size(), success);
    }
}
