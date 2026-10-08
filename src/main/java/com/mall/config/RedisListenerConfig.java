package com.mall.config;

import com.mall.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 发布订阅监听器配置（阶段 11.6）。
 *
 * <p>用途：客服工作台要"实时"感知新的转人工排队会话。实现上采用双通道：
 * <ul>
 *   <li><b>管理端前端</b>：轮询 /api/admin/sessions 拉取排队会话（简单可靠，不依赖长连接）；</li>
 *   <li><b>Java 内部</b>：用户转人工时向 Redis channel {@code chat:transfer} 发布通知，
 *       本容器订阅该 channel，收到后回调 {@link AgentTransferListener}——自动尝试分配
 *       在线空闲客服（让"用户转人工 → 立即接入"无需人工干预）。</li>
 * </ul>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisListenerConfig {

    private final RedisConnectionFactory connectionFactory;
    private final AgentTransferListener agentTransferListener;

    /** 订阅 chat:transfer 的监听容器：Redis → 回调 AgentTransferListener */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                agentTransferListener, new ChannelTopic(AgentService.TRANSFER_CHANNEL));
        log.info("Redis 订阅客服转人工通道: {}", AgentService.TRANSFER_CHANNEL);
        return container;
    }
}
