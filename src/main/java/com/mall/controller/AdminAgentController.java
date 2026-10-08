package com.mall.controller;

import com.mall.common.Result;
import com.mall.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端：人工客服与工单管理（阶段 11.6）。
 *
 * <p>接口全部受 SecurityConfig 的 {@code /api/admin/** hasRole("ADMIN")} 保护，
 * 只有管理员角色可访问。职责：
 * <ul>
 *   <li>{@code /api/admin/agents}：客服列表 / 新增 / 修改（上下线、并发）/ 删除；</li>
 *   <li>{@code /api/admin/sessions}：客服工作台会话（按状态过滤、详情含消息流）；</li>
 *   <li>{@code /api/admin/sessions/{id}/accept}：接入排队会话（自动分配空闲客服）；</li>
 *   <li>{@code /api/admin/sessions/{id}/transfer}：手动转接（回排队重新分配）；</li>
 *   <li>{@code /api/admin/sessions/{id}/message}：客服回复；</li>
 *   <li>{@code /api/admin/sessions/{id}/close}：关闭会话。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminAgentController {

    private final AgentService agentService;

    // ---------------- 客服账号管理 ----------------

    /** GET /api/admin/agents：客服列表（含用户名、当前接待数） */
    @GetMapping("/agents")
    public Result<?> listAgents() {
        return Result.success(agentService.listAgents());
    }

    /** POST /api/admin/agents：新增客服 {userId, maxConcurrent} */
    @PostMapping("/agents")
    public Result<?> createAgent(@RequestBody Map<String, Object> body) {
        Long userId = toLong(body.get("userId"));
        int maxConcurrent = body.get("maxConcurrent") == null
                ? 1 : ((Number) body.get("maxConcurrent")).intValue();
        Long agentId = agentService.createAgent(userId, maxConcurrent);
        return Result.success(Map.of("agentId", agentId));
    }

    /** PUT /api/admin/agents/{id}：更新客服 {status?, maxConcurrent?}（上下线 / 调整并发） */
    @PutMapping("/agents/{agentId}")
    public Result<?> updateAgent(@PathVariable Long agentId, @RequestBody Map<String, Object> body) {
        String status = (String) body.get("status");
        Integer maxConcurrent = body.get("maxConcurrent") == null
                ? null : ((Number) body.get("maxConcurrent")).intValue();
        agentService.updateAgent(agentId, status, maxConcurrent);
        return Result.success(null);
    }

    /** DELETE /api/admin/agents/{id}：删除客服（有接待中会话时拒绝） */
    @DeleteMapping("/agents/{agentId}")
    public Result<?> deleteAgent(@PathVariable Long agentId) {
        agentService.deleteAgent(agentId);
        return Result.success(null);
    }

    // ---------------- 客服工作台：会话 ----------------

    /** GET /api/admin/sessions?status=WAITING_FOR_HUMAN|HUMAN|CLOSED：会话列表（工作台左侧） */
    @GetMapping("/sessions")
    public Result<?> listSessions(@RequestParam(required = false) String status) {
        return Result.success(agentService.listSessions(status));
    }

    /** GET /api/admin/sessions/{id}：会话详情（基本信息 + 消息流，工作台右侧） */
    @GetMapping("/sessions/{sessionId}")
    public Result<?> sessionDetail(@PathVariable Long sessionId) {
        return Result.success(agentService.sessionDetail(sessionId));
    }

    /** POST /api/admin/sessions/{id}/accept：接入排队会话（自动分配空闲客服） */
    @PostMapping("/sessions/{sessionId}/accept")
    public Result<?> acceptSession(@PathVariable Long sessionId) {
        Long agentId = agentService.assignAgent(sessionId);
        if (agentId == null) {
            return Result.success(Map.of(
                    "accepted", false, "message", "暂无可用的在线客服，会话保持排队，客服上线后将自动接入"));
        }
        return Result.success(Map.of("accepted", true, "agentId", agentId,
                "message", "已接入人工客服，会话状态 HUMAN"));
    }

    /** POST /api/admin/sessions/{id}/transfer：手动转接（回排队队列重新分配） */
    @PostMapping("/sessions/{sessionId}/transfer")
    public Result<?> transferSession(@PathVariable Long sessionId) {
        agentService.transferSession(sessionId);
        agentService.publishTransfer(sessionId); // 通知"有会话重新排队"
        return Result.success(null);
    }

    /** POST /api/admin/sessions/{id}/message：客服回复 {content} */
    @PostMapping("/sessions/{sessionId}/message")
    public Result<?> reply(@PathVariable Long sessionId, @RequestBody Map<String, Object> body) {
        String content = (String) body.get("content");
        if (content == null || content.isBlank()) {
            return Result.error(400, "回复内容不能为空");
        }
        agentService.replyMessage(sessionId, null, content);
        return Result.success(null);
    }

    /** POST /api/admin/sessions/{id}/close：关闭会话（工单完结） */
    @PostMapping("/sessions/{sessionId}/close")
    public Result<?> closeSession(@PathVariable Long sessionId) {
        agentService.closeSession(sessionId);
        return Result.success(null);
    }

    private Long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        return o == null ? null : Long.valueOf(o.toString());
    }
}
