package com.mall.service;

import com.mall.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 人工客服管理服务（阶段 11.6：转人工客服与工单系统）。
 *
 * <p>职责：
 * <ul>
 *   <li>客服账号管理：新增（把普通用户登记为客服）、修改状态/并发上限（上下线）、删除；</li>
 *   <li><b>会话分配</b>：把 WAITING_FOR_HUMAN 排队会话分配给空闲客服（负载均衡式轮询）——</li>
 *   <li>从"在线且未满并发"的客服里，选当前接待数最少者（接待数相同时取 agent_id 最小者，
 *       天然形成轮询，避免客服忙闲不均）；分配后会话 status=HUMAN 并绑定 agent_id；</li>
 *   <li>客服工作台查询：会话列表（按状态）、会话消息、客服当前接待数；</li>
 *   <li>Redis 发布订阅：转人工时向 channel {@code chat:transfer} 发布通知，
 *       供客服工作台（轮询 + 通知双通道）及时感知新排队会话。</li>
 * </ul>
 *
 * <p>表：human_agents（客服档案）+ chat_sessions（会话，阶段 11.6 起含 agent_id）。
 *
 * <p>状态机（会话）：
 * ACTIVE（AI 接待）→ WAITING_FOR_HUMAN（排队）→ HUMAN（人工接待中）→ CLOSED（关闭）。
 * 客服状态：ONLINE / OFFLINE（手动切换），BUSY（接待数 ≥ max_concurrent 时自动置位）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    // ---------- 状态常量（与表注释一致） ----------
    public static final String S_ACTIVE = "ACTIVE";
    public static final String S_WAITING = "WAITING_FOR_HUMAN";
    public static final String S_HUMAN = "HUMAN";
    public static final String S_CLOSED = "CLOSED";
    public static final String A_ONLINE = "ONLINE";
    public static final String A_OFFLINE = "OFFLINE";
    public static final String A_BUSY = "BUSY";

    /** Redis 发布订阅 channel：用户/ AI 触发转人工时发布会话 ID，客服工作台可订阅实时感知 */
    public static final String TRANSFER_CHANNEL = "chat:transfer";

    // ========================================================================
    // 一、客服账号管理（管理端）
    // ========================================================================

    /**
     * 客服列表（JOIN users 取用户名，附带当前接待数）。
     */
    public List<Map<String, Object>> listAgents() {
        return jdbcTemplate.queryForList("""
                SELECT a.agent_id, a.user_id, u.username, a.status, a.max_concurrent,
                       (SELECT COUNT(*) FROM chat_sessions s
                         WHERE s.agent_id = a.agent_id AND s.status = 'HUMAN') AS serving_count,
                       a.create_time
                FROM human_agents a
                JOIN users u ON a.user_id = u.id
                ORDER BY a.agent_id
                """);
    }

    /**
     * 新增客服：把某个已注册用户登记为客服。
     *
     * @param userId        用户 ID（必须是已存在用户，且尚未登记为客服）
     * @param maxConcurrent 最大同时接待会话数（≥1）
     */
    @Transactional
    public Long createAgent(Long userId, int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "最大并发数必须 ≥ 1");
        }
        // 用户必须存在
        List<Long> user = jdbcTemplate.query(
                "SELECT id FROM users WHERE id = ?", (rs, i) -> rs.getLong(1), userId);
        if (user.isEmpty()) {
            throw new BusinessException(BusinessException.NOT_FOUND, "用户不存在: userId=" + userId);
        }
        // 不能重复登记（human_agents.user_id 唯一，预检查给友好提示）
        List<Long> dup = jdbcTemplate.query(
                "SELECT agent_id FROM human_agents WHERE user_id = ?",
                (rs, i) -> rs.getLong(1), userId);
        if (!dup.isEmpty()) {
            throw new BusinessException(BusinessException.PARAM_ERROR,
                    "该用户已是客服: agentId=" + dup.get(0));
        }
        jdbcTemplate.update(
                "INSERT INTO human_agents (user_id, status, max_concurrent) VALUES (?, 'ONLINE', ?)",
                userId, maxConcurrent);
        List<Long> created = jdbcTemplate.query(
                "SELECT agent_id FROM human_agents WHERE user_id = ?",
                (rs, i) -> rs.getLong(1), userId);
        log.info("新增客服: userId={}, agentId={}, maxConcurrent={}", userId, created.get(0), maxConcurrent);
        return created.get(0);
    }

    /**
     * 更新客服：上下线（status=ONLINE/OFFLINE）与并发上限。
     *
     * <p>下线时<b>不强制转出</b>已接待中的会话（客服离线只是不再接新单，
     * 已接入的会话继续由其负责），更符合真实工作台习惯；需要强转走 transferSession。
     */
    public void updateAgent(Long agentId, String status, Integer maxConcurrent) {
        requireAgent(agentId);
        if (status != null) {
            if (!A_ONLINE.equals(status) && !A_OFFLINE.equals(status)) {
                throw new BusinessException(BusinessException.PARAM_ERROR,
                        "客服状态仅支持 ONLINE / OFFLINE（BUSY 由系统自动判定）");
            }
            jdbcTemplate.update("UPDATE human_agents SET status = ? WHERE agent_id = ?", status, agentId);
        }
        if (maxConcurrent != null) {
            if (maxConcurrent < 1) {
                throw new BusinessException(BusinessException.PARAM_ERROR, "最大并发数必须 ≥ 1");
            }
            jdbcTemplate.update(
                    "UPDATE human_agents SET max_concurrent = ? WHERE agent_id = ?", maxConcurrent, agentId);
        }
        // 更新状态/并发后重算忙闲（防止手动置 ONLINE 但已满载）
        refreshAgentBusyFlag(agentId);
        log.info("更新客服: agentId={}, status={}, maxConcurrent={}", agentId, status, maxConcurrent);
    }

    /**
     * 删除客服：有接待中的会话时拒绝（避免会话悬空）。
     */
    @Transactional
    public void deleteAgent(Long agentId) {
        requireAgent(agentId);
        List<Long> serving = jdbcTemplate.query(
                "SELECT session_id FROM chat_sessions WHERE agent_id = ? AND status = 'HUMAN' LIMIT 1",
                (rs, i) -> rs.getLong(1), agentId);
        if (!serving.isEmpty()) {
            throw new BusinessException(BusinessException.PARAM_ERROR,
                    "该客服仍有接待中的会话（session=" + serving.get(0) + "），请先关闭或转接");
        }
        jdbcTemplate.update("DELETE FROM human_agents WHERE agent_id = ?", agentId);
        log.info("删除客服: agentId={}", agentId);
    }

    // ========================================================================
    // 二、会话分配与转接（核心）
    // ========================================================================

    /**
     * 分配客服：为排队中的会话挑选一个空闲客服并接入。
     *
     * <p>选择策略（负载均衡式轮询）：
     * <ol>
     *   <li>候选 = 在线（ONLINE）且当前接待数 &lt; max_concurrent 的客服；</li>
     *   <li>排序 = 接待数最少优先（繁忙程度均衡），同接待数取 agent_id 小者（稳定轮询）；</li>
     *   <li>命中 → 会话 status=HUMAN、agent_id 绑定、type=HUMAN，客服满载自动置 BUSY；</li>
     *   <li>无候选 → 返回 null（会话继续排队 WAITING，等待客服上线）。</li>
     * </ol>
     *
     * @param sessionId 排队中的会话 ID
     * @return 分配的 agentId；无空闲客服返回 null
     */
    @Transactional
    public Long assignAgent(Long sessionId) {
        // 会话必须处于排队状态
        List<Long> waiting = jdbcTemplate.query(
                "SELECT session_id FROM chat_sessions WHERE session_id = ? AND status = 'WAITING_FOR_HUMAN'",
                (rs, i) -> rs.getLong(1), sessionId);
        if (waiting.isEmpty()) {
            return null; // 状态不对（可能已被分配/关闭），幂等返回
        }
        // 找候选客服：在线且未满并发
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList("""
                SELECT a.agent_id, a.max_concurrent,
                       (SELECT COUNT(*) FROM chat_sessions s
                         WHERE s.agent_id = a.agent_id AND s.status = 'HUMAN') AS serving_count
                FROM human_agents a
                WHERE a.status = 'ONLINE'
                HAVING serving_count < a.max_concurrent
                ORDER BY serving_count ASC, a.agent_id ASC
                LIMIT 1
                """);
        if (candidates.isEmpty()) {
            log.info("无空闲客服，会话继续排队: sessionId={}", sessionId);
            return null;
        }
        Long agentId = ((Number) candidates.get(0).get("agent_id")).longValue();
        // 绑定会话：人工接待中 + 标记客服
        jdbcTemplate.update("""
                UPDATE chat_sessions
                SET status = 'HUMAN', agent_id = ?, type = 'HUMAN'
                WHERE session_id = ?
                """, agentId, sessionId);
        // 重算客服忙闲（可能刚好满载）
        refreshAgentBusyFlag(agentId);
        // 系统提示落库（前端展示"客服已接入"来源）
        jdbcTemplate.update(
                "INSERT INTO chat_messages (session_id, role, content, tokens) VALUES (?, 'system', ?, 0)",
                sessionId, "客服已接入，正在为您服务");
        log.info("分配客服成功: sessionId={}, agentId={}", sessionId, agentId);
        return agentId;
    }

    /**
     * 手动转接：把人工接待中的会话转回排队队列（清空 agent 绑定），
     * 由 {@link #assignAgent} 重新分配（可用于换客服）。
     */
    @Transactional
    public void transferSession(Long sessionId) {
        jdbcTemplate.update("""
                UPDATE chat_sessions
                SET status = 'WAITING_FOR_HUMAN', agent_id = NULL
                WHERE session_id = ?
                """, sessionId);
        log.info("会话转回排队: sessionId={}", sessionId);
    }

    /**
     * 关闭会话：客服结束接待（CLOSED），释放该客服接待数。
     */
    public void closeSession(Long sessionId) {
        jdbcTemplate.update(
                "UPDATE chat_sessions SET status = 'CLOSED' WHERE session_id = ?", sessionId);
        log.info("关闭会话: sessionId={}", sessionId);
    }

    /**
     * 客服回复：落库一条人工客服消息（role=assistant，与 AI 回复同角色，前端统一渲染）。
     */
    public void replyMessage(Long sessionId, Long agentId, String content) {
        jdbcTemplate.update(
                "INSERT INTO chat_messages (session_id, role, content, tokens) VALUES (?, 'assistant', ?, 0)",
                sessionId, content);
        log.info("客服回复: sessionId={}, agentId={}, len={}", sessionId, agentId, content.length());
    }

    /**
     * 广播转人工通知到 Redis channel（客服工作台可订阅/轮询双通道）。
     *
     * @param sessionId 排队会话 ID
     */
    public void publishTransfer(Long sessionId) {
        try {
            stringRedisTemplate.convertAndSend(TRANSFER_CHANNEL, String.valueOf(sessionId));
            log.info("已发布转人工通知: channel={}, sessionId={}", TRANSFER_CHANNEL, sessionId);
        } catch (Exception e) {
            // Redis 通知失败不影响主流程（客服工作台靠轮询兜底）
            log.warn("Redis 发布转人工通知失败（可忽略）: {}", e.getMessage());
        }
    }

    // ========================================================================
    // 三、客服工作台查询
    // ========================================================================

    /**
     * 会话列表（管理端工作台）：按状态过滤，附带用户信息与消息数。
     *
     * @param status 会话状态：WAITING_FOR_HUMAN（排队）/ HUMAN（接待中）/ CLOSED（已关闭）；null=全部
     */
    public List<Map<String, Object>> listSessions(String status) {
        String sql = """
                SELECT s.session_id, s.user_id, u.username AS user_name, s.type, s.status,
                       s.agent_id, a.username AS agent_name,
                       (SELECT COUNT(*) FROM chat_messages m WHERE m.session_id = s.session_id) AS msg_count,
                       s.create_time, s.update_time
                FROM chat_sessions s
                JOIN users u ON s.user_id = u.id
                LEFT JOIN human_agents ag ON s.agent_id = ag.agent_id
                LEFT JOIN users a ON ag.user_id = a.id
                """;
        if (status == null || status.isBlank()) {
            return jdbcTemplate.queryForList(sql + " ORDER BY s.update_time DESC LIMIT 100");
        }
        return jdbcTemplate.queryForList(
                sql + " WHERE s.status = ? ORDER BY s.update_time DESC LIMIT 100", status);
    }

    /**
     * 会话详情：基本信息 + 完整消息流（工作台右侧展示）。
     */
    public Map<String, Object> sessionDetail(Long sessionId) {
        List<Map<String, Object>> sessions = jdbcTemplate.queryForList("""
                SELECT s.session_id, s.user_id, u.username AS user_name, s.type, s.status,
                       s.agent_id, a.username AS agent_name, s.create_time
                FROM chat_sessions s
                JOIN users u ON s.user_id = u.id
                LEFT JOIN human_agents ag ON s.agent_id = ag.agent_id
                LEFT JOIN users a ON ag.user_id = a.id
                WHERE s.session_id = ?
                """, sessionId);
        if (sessions.isEmpty()) {
            throw new BusinessException(BusinessException.NOT_FOUND, "会话不存在: " + sessionId);
        }
        Map<String, Object> detail = sessions.get(0);
        List<Map<String, Object>> messages = jdbcTemplate.queryForList("""
                SELECT message_id, role, content, create_time
                FROM chat_messages
                WHERE session_id = ?
                ORDER BY message_id ASC
                """, sessionId);
        detail.put("messages", messages);
        return detail;
    }

    // ========================================================================
    // 四、内部工具方法
    // ========================================================================

    private void requireAgent(Long agentId) {
        List<Long> found = jdbcTemplate.query(
                "SELECT agent_id FROM human_agents WHERE agent_id = ?",
                (rs, i) -> rs.getLong(1), agentId);
        if (found.isEmpty()) {
            throw new BusinessException(BusinessException.NOT_FOUND, "客服不存在: " + agentId);
        }
    }

    /**
     * 重算客服忙闲标记：接待数 ≥ 最大并发 → BUSY，否则回到 ONLINE。
     * 用于分配/上下线后保持状态准确。
     *
     * <p><b>注意：手动 OFFLINE 不能被覆盖</b>——客服主动下线时即使接待数未满
     * 也不能自动改回 ONLINE（否则"下线"形同虚设，排队测试也会失真）。
     * 只有当前状态 ONLINE/BUSY 时才参与忙闲自动判定。
     */
    private void refreshAgentBusyFlag(Long agentId) {
        List<Map<String, Object>> row = jdbcTemplate.queryForList("""
                SELECT a.status AS cur_status, a.max_concurrent,
                       (SELECT COUNT(*) FROM chat_sessions s
                         WHERE s.agent_id = a.agent_id AND s.status = 'HUMAN') AS serving_count
                FROM human_agents a WHERE a.agent_id = ?
                """, agentId);
        if (row.isEmpty()) {
            return;
        }
        String current = (String) row.get(0).get("cur_status");
        if (A_OFFLINE.equals(current)) {
            return; // 手动离线：保持离线，不做忙闲自动切换
        }
        int max = ((Number) row.get(0).get("max_concurrent")).intValue();
        int serving = ((Number) row.get(0).get("serving_count")).intValue();
        String target = serving >= max ? A_BUSY : A_ONLINE;
        jdbcTemplate.update("UPDATE human_agents SET status = ? WHERE agent_id = ?", target, agentId);
    }
}
