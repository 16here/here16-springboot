package com.mall.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.config.RocketMQProperties;
import com.mall.dto.mq.OrderTimeoutMessage;
import com.mall.service.OrderService;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 订单超时消费者（Phase 2 延迟消息替代定时轮询作为主防线）。
 *
 * <p>下单成功后，生产者发一条"延迟 30 分钟"的消息（RocketMQ 延迟级别 16）；
 * 本消费者在延迟到点后收到消息，检查该订单是否仍为"待付款"：
 * <ul>
 *   <li>仍是待付款 → 走状态机 TIMEOUT 自动取消 + 恢复库存；</li>
 *   <li>已支付/已取消 → 静默跳过（幂等由状态机保证）。</li>
 * </ul>
 *
 * <p>为什么比定时任务好：定时轮询是"每分钟扫全表"，扫描有延迟且做大量无用查询；
 * 延迟消息是"每单精准定时"，到点只处理这一单。真实电商两者都留——
 * 定时任务（OrderTimeoutTask）作为最终兜底，防消息丢失/积压。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutConsumer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RocketMQProperties properties;
    private final OrderService orderService;

    /** 真实超时阈值（分钟，与 OrderTimeoutTask / 下单发送时一致） */
    @Value("${order.timeout-minutes:30}")
    private int timeoutMinutes;

    private DefaultMQPushConsumer consumer;

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            DefaultMQPushConsumer c = new DefaultMQPushConsumer("mall-consumer-order-timeout");
            c.setNamesrvAddr(properties.getNameServer());
            // 从最新开始消费：超时消息只要"到点后的那一条"，历史积压无意义
            c.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
            c.subscribe(properties.getTopics().getOrderTimeout(), "*");
            c.registerMessageListener(new MessageListenerConcurrently() {
                @Override
                public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs,
                                                                ConsumeConcurrentlyContext ctx) {
                    for (MessageExt msg : msgs) {
                        try {
                            OrderTimeoutMessage m = MAPPER.readValue(
                                    new String(msg.getBody(), StandardCharsets.UTF_8), OrderTimeoutMessage.class);
                            // 延迟到点触发检查：OrderService 内部按真实阈值再做一次时间/状态校验
                            // （幂等 + 已支付/已取消跳过 + 未到阈值跳过）
                            orderService.timeoutCancelIfDue(m.getOrderId(), timeoutMinutes);
                            log.info("订单超时延迟消息已消费: orderId={}, orderNo={}", m.getOrderId(), m.getOrderNo());
                        } catch (Exception e) {
                            log.error("订单超时消息消费失败（进入重试）: msgId={}, err={}", msg.getMsgId(), e.getMessage());
                            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        } finally {
                            // Phase 3 分表：消费者线程常驻，OrderService 内部设置的分表路由
                            // 没有 HTTP 请求过滤器清理，必须在消费结束后手动清空，
                            // 防止路由串到下一次消费
                            com.mall.common.ShardingContext.clear();
                        }
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                }
            });
            c.start();
            this.consumer = c;
            log.info("RocketMQ 订单超时消费者启动成功: topic={}", properties.getTopics().getOrderTimeout());
        } catch (Exception e) {
            log.warn("RocketMQ 订单超时消费者启动失败（由 OrderTimeoutTask 定时任务兜底）: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (consumer != null) {
            try {
                consumer.shutdown();
            } catch (Exception e) {
                log.warn("RocketMQ 订单超时消费者关闭异常: {}", e.getMessage());
            }
        }
    }
}
