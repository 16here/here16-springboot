package com.mall.config;

import com.mall.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 转人工通知监听器（阶段 11.6）。
 *
 * <p>收到 Redis channel {@code chat:transfer} 的消息（消息体为排队会话 ID）后：
 * <ol>
 *   <li>记录日志（可观测：谁在什么时刻转人工）；</li>
 *   <li>尝试自动分配在线空闲客服——若 ChatController 转人工时已分配成功则幂等跳过
 *       （会话已是 HUMAN 不是 WAITING）；若当时无空闲客服，等客服上线后收到
 *       重新发布的排队通知时自动接入。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTransferListener implements MessageListener {

    private final AgentService agentService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            Long sessionId = Long.valueOf(payload.trim());
            log.info("收到转人工通知: sessionId={}", sessionId);
            Long agentId = agentService.assignAgent(sessionId);
            if (agentId != null) {
                log.info("自动分配客服成功: sessionId={}, agentId={}", sessionId, agentId);
            }
        } catch (NumberFormatException e) {
            log.warn("转人工通知消息体非法（忽略）: {}", payload);
        }
    }
}
