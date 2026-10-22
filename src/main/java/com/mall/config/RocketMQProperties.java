package com.mall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 配置绑定（Phase 2 消息化）。
 *
 * <p>读取 application.yml 的 {@code rocketmq.*} 配置段，供生产者/消费者共用：
 * <pre>
 * rocketmq:
 *   name-server: 127.0.0.1:9876
 *   producer-group: mall-producer
 *   enabled: true
 *   topics:
 *     order-timeout: mall-order-timeout
 *     pay-success: mall-pay-success
 *     behavior: mall-behavior
 *     cache-evict: mall-cache-evict
 * </pre>
 *
 * <p>{@code enabled=false} 时消息链路整体降级：发送方直接走"本地兜底"
 * （行为同步写库、双删用本地定时线程），主业务完全不依赖 MQ 也能跑通。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rocketmq")
public class RocketMQProperties {

    /** NameServer 地址（127.0.0.1:9876；生产为集群地址逗号分隔） */
    private String nameServer = "127.0.0.1:9876";

    /** 生产者组名 */
    private String producerGroup = "mall-producer";

    /** 总开关：false 时全部消息逻辑退化本地兜底（不连 MQ） */
    private boolean enabled = true;

    /** 同步发送超时（毫秒） */
    private long sendTimeoutMs = 3000;

    /** 各业务 Topic 名称 */
    private Topics topics = new Topics();

    /** Topic 名集合（内部类，字段与 yml 的 rocketmq.topics.* 一一对应） */
    @Data
    public static class Topics {
        /** 订单超时延迟消息（下单后发，消费者到点检查并取消超时未支付订单） */
        private String orderTimeout = "mall-order-timeout";
        /** 支付成功异步通知（记录购买行为 + 通知） */
        private String paySuccess = "mall-pay-success";
        /** 用户行为埋点（浏览/收藏/加购/购买，异步写 user_behaviors） */
        private String behavior = "mall-behavior";
        /** 缓存延迟删除（延迟消息替代本地定时线程） */
        private String cacheEvict = "mall-cache-evict";
    }

    /**
     * RocketMQ 固定延迟级别表（4.9.x 共 18 级，从 1 开始编号）：
     * <pre>
     * 1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h
     * </pre>
     * 本项目订单超时默认 30 分钟 → 级别 16。
     *
     * @param minutes 分钟数（0 表示不延迟）
     * @return 对应的延迟级别；没有精确匹配时向上取最近的级别
     */
    public static int delayLevelOfMinutes(int minutes) {
        if (minutes <= 0) {
            return 0; // 0 = 不延迟，立即投递
        }
        // 分钟 → 延迟级别（下标从 1 开始；找到第一个 >= 目标分钟的级别）
        int[] levels = {1, 5, 10, 30, 60, 120, 180, 240, 300, 360, 420, 480, 540, 600, 1200, 1800, 3600, 7200};
        for (int i = 0; i < levels.length; i++) {
            if (minutes * 60 <= levels[i]) {
                return i + 1; // level 编号 = 下标 + 1（1s 是第 1 级）
            }
        }
        return 18; // 超过 2 小时按最大级别 2h
    }
}
