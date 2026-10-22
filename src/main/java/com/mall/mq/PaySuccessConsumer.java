package com.mall.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.config.RocketMQProperties;
import com.mall.dto.mq.PaySuccessMessage;
import com.mall.repository.OrderItemRepository;
import com.mall.repository.UserBehaviorRepository;
import com.mall.service.RecommendService;
import com.mall.entity.OrderItem;
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
 * 支付成功通知消费者（Phase 2 支付回调异步化）。
 *
 * <p>支付回调主线程只做强一致性动作（验签 → 更新流水 → 订单状态 → 扣库存 → 核销券），
 * 做完后发一条"支付成功"消息就立即返回 success 给支付平台；
 * 本消费者异步完成"收尾动作"：
 * <ul>
 *   <li>给订单明细逐条记录购买行为（PURCHASE，推荐系统最强信号）；</li>
 *   <li>发送通知（本项目以日志输出模拟短信/站内信，真实项目可接短信网关/推送）。</li>
 * </ul>
 *
 * <p>为什么把埋点/通知拆到消息里：回调响应时间对支付平台很重要（超时会被重试轰炸），
 * 收尾动作可容忍几秒延迟，交给异步更合理。行为记录失败只影响推荐效果，不影响支付结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaySuccessConsumer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RocketMQProperties properties;
    private final RecommendService recommendService;
    private final OrderItemRepository orderItemRepository;
    private final UserBehaviorRepository userBehaviorRepository;

    private DefaultMQPushConsumer consumer;

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            DefaultMQPushConsumer c = new DefaultMQPushConsumer("mall-consumer-pay-success");
            c.setNamesrvAddr(properties.getNameServer());
            c.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
            c.subscribe(properties.getTopics().getPaySuccess(), "*");
            c.registerMessageListener(new MessageListenerConcurrently() {
                @Override
                public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs,
                                                                ConsumeConcurrentlyContext ctx) {
                    for (MessageExt msg : msgs) {
                        try {
                            PaySuccessMessage m = MAPPER.readValue(
                                    new String(msg.getBody(), StandardCharsets.UTF_8), PaySuccessMessage.class);
                            // 1. 异步记录购买行为（逐条明细 PURCHASE，权重 5）
                            List<OrderItem> items = orderItemRepository.selectByOrderId(m.getOrderId());
                            for (OrderItem item : items) {
                                recommendService.recordSync(m.getUserId(), item.getProductId(),
                                        item.getSkuId(), UserBehaviorRepository.TYPE_PURCHASE);
                            }
                            // 2. 异步发送通知（mock：日志模拟短信/站内信；真实项目在此接短信网关）
                            log.info("[异步通知] 用户 {} 的订单 {} 支付成功（支付方式 {}，流水 {}），"
                                            + "订单商品 {} 件已进入待发货，祝您购物愉快。",
                                    m.getUserId(), m.getOrderId(), m.getPayMethod(), m.getPaymentNo(), items.size());
                            log.debug("支付成功消息已消费: orderId={}, items={}", m.getOrderId(), items.size());
                        } catch (Exception e) {
                            log.error("支付成功消息消费失败（进入重试）: msgId={}, err={}", msg.getMsgId(), e.getMessage());
                            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        }
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                }
            });
            c.start();
            this.consumer = c;
            log.info("RocketMQ 支付成功消费者启动成功: topic={}", properties.getTopics().getPaySuccess());
        } catch (Exception e) {
            log.warn("RocketMQ 支付成功消费者启动失败（收尾动作改为同步执行）: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (consumer != null) {
            try {
                consumer.shutdown();
            } catch (Exception e) {
                log.warn("RocketMQ 支付成功消费者关闭异常: {}", e.getMessage());
            }
        }
    }
}
