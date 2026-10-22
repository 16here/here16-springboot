package com.mall.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.config.RocketMQProperties;
import com.mall.dto.mq.CacheEvictMessage;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 缓存延迟删除消费者（Phase 2 双删消息化）。
 *
 * <p>Phase 1 的延迟双删，第二次删除用的是"进程内定时线程"——JVM 重启任务就丢、
 * 多实例部署不共享，不够可靠。现在改为：写操作发一条 RocketMQ 延迟消息
 * （延迟 1 秒，level 1），由本消费者在延迟到点后删除 Redis key。
 *
 * <p>为什么延迟 1 秒再删：写库后"旧值回填缓存"的竞态窗口通常在几十毫秒内，
 * 1 秒后删除可确保把回填的旧缓存清掉；消息由 broker 持久化，即使删除动作
 * 发生时应用恰好重启，broker 也会在新实例起来后重新投递（消费组 offset 保存）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheEvictConsumer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RocketMQProperties properties;
    private final StringRedisTemplate stringRedisTemplate;

    private DefaultMQPushConsumer consumer;

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            DefaultMQPushConsumer c = new DefaultMQPushConsumer("mall-consumer-cache-evict");
            c.setNamesrvAddr(properties.getNameServer());
            c.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
            c.subscribe(properties.getTopics().getCacheEvict(), "*");
            c.registerMessageListener(new MessageListenerConcurrently() {
                @Override
                public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs,
                                                                ConsumeConcurrentlyContext ctx) {
                    for (MessageExt msg : msgs) {
                        try {
                            CacheEvictMessage m = MAPPER.readValue(
                                    new String(msg.getBody(), StandardCharsets.UTF_8), CacheEvictMessage.class);
                            stringRedisTemplate.delete(m.getRedisKey());
                            log.debug("缓存延迟删除已执行: key={}", m.getRedisKey());
                        } catch (Exception e) {
                            log.error("缓存延迟删除消息消费失败（进入重试）: msgId={}, err={}", msg.getMsgId(), e.getMessage());
                            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        }
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                }
            });
            c.start();
            this.consumer = c;
            log.info("RocketMQ 缓存删除消费者启动成功: topic={}", properties.getTopics().getCacheEvict());
        } catch (Exception e) {
            log.warn("RocketMQ 缓存删除消费者启动失败（双删回退为本地定时线程）: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (consumer != null) {
            try {
                consumer.shutdown();
            } catch (Exception e) {
                log.warn("RocketMQ 缓存删除消费者关闭异常: {}", e.getMessage());
            }
        }
    }
}
