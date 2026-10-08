package com.mall.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 客服聊天服务（阶段 11.5）：聊天记录与会话的持久化。
 *
 * <p>为什么用 JdbcTemplate 而不是 MyBatis Mapper：
 * <ul>
 *   <li>chat_sessions / chat_messages 属于 AI 客服子系统，只有少量简单
 *       INSERT/SELECT/UPDATE，不涉及复杂关联查询；</li>
 *   <li>JdbcTemplate 直接手写 SQL，比"实体 + Mapper 接口 + XML"少三层样板代码，
 *       且语义一目了然，符合"不为简单需求过度设计"的原则；</li>
 *   <li>项目已通过 mybatis starter 传递引入 spring-jdbc，JdbcTemplate 可直接注入。</li>
 * </ul>
 *
 * <p>表结构（sql/migrate_stage11_ai.sql，阶段 11.0 已建）：
 * <ul>
 *   <li>chat_sessions：会话（user_id, type=AI/HUMAN, status=ACTIVE/WAITING_FOR_HUMAN/CLOSED）</li>
 *   <li>chat_messages：消息（session_id, role=user/assistant/system/tool, content, tokens）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 获取（或创建）当前用户的 AI 会话。
     *
     * <p>会话规则：每个用户同时只有一个进行中的 AI 会话（type=AI 且 status=ACTIVE）；
     * 有则复用（按最近活跃排序取最新），没有则新建。这样聊天记录按会话归档，
     * 后续"转人工 / 人工接管"可以直接挂到同一会话上。
     *
     * @param userId 当前登录用户 ID
     * @return 会话 ID
     */
    @Transactional
    public Long getOrCreateSession(Long userId) {
        // 1) 查进行中的 AI 会话（复用最近的）。
        //    注意：用 query 而非 queryForObject——查无记录时 query 返回空列表，
        //    而 queryForObject 会抛 EmptyResultDataAccessException（新手易踩坑）。
        List<Long> existing = jdbcTemplate.query(
                "SELECT session_id FROM chat_sessions WHERE user_id = ? AND type = 'AI' "
                        + "AND status = 'ACTIVE' ORDER BY update_time DESC LIMIT 1",
                (rs, rowNum) -> rs.getLong(1), userId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        // 2) 没有则新建（type=AI，status=ACTIVE；create_time/update_time 由 DB 默认值生成）
        jdbcTemplate.update(
                "INSERT INTO chat_sessions (user_id, type, status) VALUES (?, 'AI', 'ACTIVE')",
                userId);
        List<Long> created = jdbcTemplate.query(
                "SELECT session_id FROM chat_sessions WHERE user_id = ? AND type = 'AI' "
                        + "AND status = 'ACTIVE' ORDER BY session_id DESC LIMIT 1",
                (rs, rowNum) -> rs.getLong(1), userId);
        Long sessionId = created.isEmpty() ? null : created.get(0);
        log.info("创建 AI 会话: userId={}, sessionId={}", userId, sessionId);
        return sessionId;
    }

    /**
     * 保存一条聊天记录到 chat_messages。
     *
     * @param sessionId 会话 ID
     * @param role      消息角色：user（用户）/ assistant（AI 回复）
     * @param content   消息内容
     */
    public void saveMessage(Long sessionId, String role, String content) {
        if (content == null || content.isEmpty()) {
            return; // 空内容不落库（避免存无意义记录）
        }
        jdbcTemplate.update(
                "INSERT INTO chat_messages (session_id, role, content, tokens) VALUES (?, ?, ?, 0)",
                sessionId, role, content);
        log.debug("保存聊天记录: session={}, role={}, len={}", sessionId, role, content.length());
    }

    /**
     * 将会话标记为"等待转人工"（WAITING_FOR_HUMAN）。
     *
     * <p>后续阶段（转人工体系）会轮询该状态把会话分配给在线人工客服。
     */
    public void markWaitingHuman(Long sessionId) {
        jdbcTemplate.update(
                "UPDATE chat_sessions SET status = 'WAITING_FOR_HUMAN' WHERE session_id = ?",
                sessionId);
        log.info("会话转人工: sessionId={}", sessionId);
    }
}
