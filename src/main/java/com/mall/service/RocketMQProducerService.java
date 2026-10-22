package com.mall.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.config.RocketMQProperties;
import com.mall.dto.mq.BehaviorMessage;
import com.mall.dto.mq.CacheEvictMessage;
import com.mall.dto.mq.OrderTimeoutMessage;
import com.mall.dto.mq.PaySuccessMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * RocketMQ 消息生产者封装（Phase 2 消息化）。
 *
 * <p>职责：把"发消息"这件事收敛到一个类里，业务方只调用语义化方法：
 * <pre>
 *   producer.sendBehavior(userId, productId, skuId, type)   // 行为埋点
 *   producer.sendOrderTimeout(orderId, orderNo, minutes)    // 订单超时延迟消息
 *   producer.sendPaySuccess(orderId, userId, paymentNo)     // 支付成功通知
 *   producer.sendCacheEvict(redisKey)                       // 缓存延迟删除
 * </pre>
 *
 * <p>关键设计——<b>MQ 挂了业务不能挂</b>：
 * <ul>
 *   <li>启动时连接失败只记警告，不阻止应用启动；</li>
 *   <li>发送失败返回 {@code false} 并记日志，由调用方决定兜底策略
 *       （行为→同步写库、双删→本地线程再删一次），保证主流程可用；</li>
 *   <li>{@code rocketmq.enabled=false} 时 send 直接返回 false（走兜底），完全不碰 MQ。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RocketMQProducerService {

    /** 消息体 JSON 序列化（独立实例，避免与其他 ObjectMapper Bean 的配置耦合） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RocketMQProperties properties;

    /** RocketMQ 生产者实例（惰性使用；启动失败时保持 null，发送直接返回 false 走兜底） */
    private DefaultMQProducer producer;

    /**
     * 初始化生产者并连接 NameServer。
     *
     * <p>允许失败：MQ 未部署/未启动时，应用仍可正常运行（消息链路全部降级为本地兜底）。
     */
    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("RocketMQ 已关闭（rocketmq.enabled=false），消息链路走本地兜底");
            return;
        }
        try {
            DefaultMQProducer p = new DefaultMQProducer(properties.getProducerGroup());
            p.setNamesrvAddr(properties.getNameServer());
            p.setSendMsgTimeout((int) properties.getSendTimeoutMs());
            // 消息体默认 4M；行为/通知消息远小于此，不额外设置
            p.start();
            this.producer = p;
            log.info("RocketMQ 生产者初始化成功: namesrv={}, group={}",
                    properties.getNameServer(), properties.getProducerGroup());
        } catch (Exception e) {
            log.warn("RocketMQ 生产者初始化失败（消息链路降级为本地兜底）: {}", e.getMessage());
        }
    }

    /** 应用关闭时释放生产者连接 */
    @PreDestroy
    public void destroy() {
        if (producer != null) {
            try {
                producer.shutdown();
            } catch (Exception e) {
                log.warn("RocketMQ 生产者关闭异常: {}", e.getMessage());
            }
        }
    }

    // ==================== 业务语义化发送方法 ====================

    /**
     * 发送用户行为消息（浏览/收藏/加购/购买）。
     *
     * @return true=已入队；false=MQ 不可用（调用方应同步写库兜底）
     */
    public boolean sendBehavior(Long userId, Long productId, Long skuId, String type) {
        return send(properties.getTopics().getBehavior(), "behavior",
                new BehaviorMessage(userId, productId, skuId, type));
    }

    /**
     * 发送订单超时延迟消息（下单成功后立即调用）。
     *
     * @param minutes 超时分钟数，映射到 RocketMQ 固定延迟级别（见 RocketMQProperties.delayLevelOfMinutes）
     * @return true=已入队（延迟到点后消费者检查该订单）
     */
    public boolean sendOrderTimeout(Long orderId, String orderNo, int minutes) {
        int level = RocketMQProperties.delayLevelOfMinutes(minutes);
        return send(properties.getTopics().getOrderTimeout(), "order-timeout",
                new OrderTimeoutMessage(orderId, orderNo), level);
    }

    /**
     * 发送支付成功通知消息（支付回调成功后调用；消费者记录购买行为 + 发送通知）。
     *
     * @return true=已入队
     */
    public boolean sendPaySuccess(Long orderId, Long userId, String paymentNo, String payMethod) {
        return send(properties.getTopics().getPaySuccess(), "pay-success",
                new PaySuccessMessage(orderId, userId, paymentNo, payMethod));
    }

    /**
     * 发送缓存延迟删除消息（延迟 1 秒后由消费者删除 Redis key）。
     *
     * @return true=已入队
     */
    public boolean sendCacheEvict(String redisKey) {
        return send(properties.getTopics().getCacheEvict(), "cache-evict",
                new CacheEvictMessage(redisKey), 1); // level 1 = 1s
    }

    // ==================== 通用发送 ====================

    /**
     * 发送普通消息（立即投递）。
     *
     * @param topic   Topic 名
     * @param tag     Tag（消息二级分类，消费者可按 tag 过滤）
     * @param payload 消息体（Jackson 序列化为 JSON）
     * @return true=发送成功；false=MQ 不可用或发送异常（调用方自行兜底）
     */
    private boolean send(String topic, String tag, Object payload) {
        return send(topic, tag, payload, 0);
    }

    /**
     * 发送消息（支持延迟级别）。
     *
     * @param delayLevel RocketMQ 延迟级别（0=不延迟；1=1s，2=5s，… 见 RocketMQProperties）
     */
    private boolean send(String topic, String tag, Object payload, int delayLevel) {
        if (!properties.isEnabled() || producer == null) {
            // MQ 未启用或初始化失败：由调用方走本地兜底，这里只记录
            log.debug("RocketMQ 不可用，跳过消息发送: topic={}", topic);
            return false;
        }
        try {
            byte[] body = MAPPER.writeValueAsBytes(payload);
            Message message = new Message(topic, tag, body);
            if (delayLevel > 0) {
                message.setDelayTimeLevel(delayLevel);
            }
            SendResult result = producer.send(message);
            log.debug("消息发送成功: topic={}, tag={}, msgId={}, delayLevel={}",
                    topic, tag, result.getMsgId(), delayLevel);
            return true;
        } catch (Exception e) {
            log.warn("消息发送失败: topic={}, tag={}, err={}", topic, tag, e.getMessage());
            return false;
        }
    }
}
