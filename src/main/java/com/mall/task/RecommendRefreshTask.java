package com.mall.task;

import com.mall.service.RedisRecommendService;
import com.mall.service.RecommendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 推荐缓存刷新定时任务（阶段 10）。
 *
 * <p>业务背景：推荐结果依赖用户行为数据，行为在持续产生，
 * 缓存不刷新会越推越旧。本任务做一件事：
 * <b>预热热度推荐缓存</b>——把全站热度 Top 20 按最新行为实时算好写进 Redis，
 * 游客首页首次访问直接命中（秒开）；登录用户个性化与商品相关缓存
 * 按 TTL 自然过期重算。
 *
 * <p>设计说明：
 * <ul>
 *   <li><b>频率 10 分钟</b>（fixedDelay=600000）：行为统计窗口以天计，
 *       10 分钟刷新足够及时，又不会给数据库压力；</li>
 *   <li><b>只预热不删除</b>：预热本身就是"按最新行为重算并覆盖"，
 *       写完再删会自相矛盾；</li>
 *   <li><b>失败不阻塞</b>：预热任一步异常只记日志，任务继续。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendRefreshTask {

    private final RecommendService recommendService;

    /**
     * 每 10 分钟刷新一次推荐缓存（启动 30 秒后首次执行，等 Redis 就绪）。
     *
     * <p>本任务<b>只做预热、不做删除</b>：home(null, 20) 会实时计算最新热度
     * 并写入 recommend:hot 与 recommend:home:guest（游客首页 + 个性化兜底共用），
     * 写完后删除会自相矛盾（白预热）。登录用户个性化缓存
     * （recommend:home:&lt;userId&gt;）与商品相关缓存（recommend:product:*）
     * 按 TTL（默认 600 秒）自然过期后由下次请求按最新行为重算，
     * 行为反映延迟最多一个 TTL，对本项目体量足够及时。
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 30_000)
    public void refresh() {
        try {
            // 预热热度推荐缓存（实时计算最新热度，覆盖热门推荐 + 游客首页）
            recommendService.home(null, 20);
            log.info("推荐缓存已刷新：热度预热完成");
        } catch (Exception e) {
            log.warn("推荐缓存刷新异常（下次任务继续）: {}", e.getMessage());
        }
    }
}
