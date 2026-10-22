package com.mall.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.config.RocketMQProperties;
import com.mall.dto.mq.BehaviorMessage;
import com.mall.service.RecommendService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 用户行为消费者（Phase 2 异步埋点）。
 *
 * <p>消费 {@code mall-behavior} topic 的消息：浏览/收藏/加购/购买行为不再由
 * 业务线程同步写库，而是入队后由本消费者异步写入 user_behaviors 表。
 * 效果：商品详情/加购接口响应更快（省一次数据库写）；埋点失败也不影响主业务。
 *
 * <p>消费失败处理：返回 {@code RECONSUME_LATER} 触发 RocketMQ 自动重试（默认 16 次），
 * 重试仍失败的消息进入死信队列（开发环境可不配死信，靠日志排查）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorConsumer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RocketMQProperties properties;
    private final RecommendService recommendService;

    private DefaultMQPushConsumer consumer;

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            return; // MQ 关闭时不启动消费者（发送方走本地兜底）
        }
        try {
            DefaultMQPushConsumer c = new DefaultMQPushConsumer("mall-consumer-behavior");
            c.setNamesrvAddr(properties.getNameServer());
            // 从头消费：行为是"量大数据"，开发环境重启动后希望不丢历史埋点
            c.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            c.subscribe(properties.getTopics().getBehavior(), "*");
            c.registerMessageListener(new MessageListenerConcurrently() {
                @Override
                public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs,
                                                                ConsumeConcurrentlyContext ctx) {
                    for (MessageExt msg : msgs) {
                        try {
                            BehaviorMessage m = MAPPER.readValue(
                                    new String(msg.getBody(), StandardCharsets.UTF_8), BehaviorMessage.class);
                            // 直接写库（recordSync 不经过消息层，避免循环发送）
                            recommendService.recordSync(m.getUserId(), m.getProductId(), m.getSkuId(), m.getType());
                            log.debug("行为消息已消费: userId={}, productId={}, type={}",
                                    m.getUserId(), m.getProductId(), m.getType());
                        } catch (Exception e) {
                            log.error("行为消息消费失败（进入重试）: msgId={}, err={}", msg.getMsgId(), e.getMessage());
                            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        }
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                }
            });
            c.start();
            this.consumer = c;
            log.info("RocketMQ 行为消费者启动成功: topic={}", properties.getTopics().getBehavior());
        } catch (Exception e) {
            log.warn("RocketMQ 行为消费者启动失败（行为埋点降级为发送方同步写库）: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (consumer != null) {
            try {
                consumer.shutdown();
            } catch (Exception e) {
                log.warn("RocketMQ 行为消费者关闭异常: {}", e.getMessage());
            }
        }
    }
}
