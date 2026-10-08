package com.mall.controller;

import com.mall.common.BusinessException;
import com.mall.common.CurrentUser;
import com.mall.common.Result;
import com.mall.dto.ChatMessageDTO;
import com.mall.service.AgentService;
import com.mall.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 客服对话接口（/api/chat/message，阶段 11.5）。
 *
 * <p>职责链：前端聊天窗 → Java（本控制器）→ Python AI 服务（SSE 流）→ Java 原样透传 → 前端。
 *
 * <p>两个分支：
 * <ul>
 *   <li>type=AI（默认）：把用户消息落库，然后通过 WebClient 转发给 Python 的
 *       POST /api/chat/completions，把返回的 SSE 流 <b>原样</b>透传给前端
 *       （SseEmitter 是 Spring MVC 原生的 SSE 通道）；</li>
 *   <li>type=HUMAN：用户点击"转人工"，不调 Python，直接把会话标记为
 *       WAITING_FOR_HUMAN 并返回提示（转人工分配属后续阶段）。</li>
 * </ul>
 *
 * <p>为什么用 SseEmitter + WebClient（而非 WebFlux Controller）：
 * <ul>
 *   <li>本服务是 Spring MVC（servlet）应用，SseEmitter 是 MVC 的 SSE 原生方案，
 *       与现有 Filter/Security/拦截器完全兼容；</li>
 *   <li>WebClient（Reactive）负责"异步拉取 Python 的流"，其回调把每个数据块
 *       转交 SseEmitter——这是"Reactive 客户端 → Servlet 服务端"的标准桥接模式；
 *       二者各司其职，不引入整套 WebFlux 处理链。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final WebClient webClient;
    private final AgentService agentService;

    /** Python AI 服务地址（application.yml: mall.ai-service.base-url） */
    @Value("${mall.ai-service.base-url}")
    private String aiBaseUrl;

    /**
     * 用户主动转人工（阶段 11.6）。
     *
     * <p>流程：落库用户消息 → 会话状态 WAITING_FOR_HUMAN（排队）→
     * 立即尝试分配空闲客服（有在线客服则直接接入 HUMAN）→
     * Redis 发布订阅通知客服工作台。返回当前会话状态供前端展示。
     */
    @PostMapping("/transfer")
    public Result<?> transfer(@RequestBody Map<String, Object> body) {
        Long userId = currentUserId();
        Long sessionId = chatService.getOrCreateSession(userId);
        String message = body.get("message") == null ? "" : body.get("message").toString();

        // 1) 用户请求落库（带 [转人工] 标记，客服工作台一眼识别诉求）
        chatService.saveMessage(sessionId, "user",
                message.isBlank() ? "[转人工] 用户请求人工客服" : "[转人工] " + message);
        // 2) 排队
        chatService.markWaitingHuman(sessionId);
        // 3) 立即尝试分配（有在线空闲客服直接接入）
        Long agentId = agentService.assignAgent(sessionId);
        // 4) Redis 广播通知（客服工作台实时感知）
        agentService.publishTransfer(sessionId);

        return Result.success(Map.of(
                "sessionId", sessionId,
                "status", agentId == null ? AgentService.S_WAITING : AgentService.S_HUMAN,
                "agentId", agentId == null ? null : agentId,
                "message", agentId == null
                        ? "已为您转接人工客服，正在排队，请稍候……"
                        : "客服已接入，正在为您服务"));
    }

    /**
     * 查询会话状态（前端轮询，阶段 11.6）。
     *
     * <p>用户点击转人工后前端定时调用，直到 status=HUMAN（客服已接入）
     * 或 CLOSED（会话结束）。返回当前状态 + 接待客服昵称。
     */
    @GetMapping("/status")
    public Result<?> status(@RequestParam Long sessionId) {
        Map<String, Object> row = agentService.sessionDetail(sessionId);
        return Result.success(Map.of(
                "sessionId", sessionId,
                "status", row.get("status"),
                "type", row.get("type"),
                "agentName", row.get("agent_name")));
    }

    /**
     * 发送一条客服消息。
     *
     * <p>AI 模式响应为 text/event-stream（SseEmitter 自动设置），SSE 事件格式与
     * Python 侧完全一致（status → delta×N → done / error），前端可直接复用
     * 同一套 SSE 解析逻辑。
     */
    @PostMapping("/message")
    public Object message(@Valid @RequestBody ChatMessageDTO dto) {
        Long userId = currentUserId();
        Long sessionId = chatService.getOrCreateSession(userId);

        // ---------- 分支 1：转人工模式下的用户留言 ----------
        // （11.6 起用户主动转人工走 /api/chat/transfer；此处保留 HUMAN 分支兼容旧前端，
        //   人工模式下用户消息直接落库，客服在工作台可实时看到）
        if ("HUMAN".equalsIgnoreCase(dto.getType())) {
            chatService.saveMessage(sessionId, "user", dto.getMessage());
            // 若会话还没在排队（旧前端直接发 HUMAN 消息），顺手转人工
            List<Map<String, Object>> st = agentService.listSessions(null);
            boolean waiting = st.stream()
                    .anyMatch(s -> ((Number) s.get("session_id")).longValue() == sessionId
                            && (AgentService.S_WAITING.equals(s.get("status"))
                            || AgentService.S_HUMAN.equals(s.get("status"))));
            if (!waiting) {
                chatService.markWaitingHuman(sessionId);
                agentService.assignAgent(sessionId);
                agentService.publishTransfer(sessionId);
            }
            return Result.success(Map.of("sessionId", sessionId, "message", "已发送，人工客服将尽快回复。"));
        }

        // ---------- 分支 2：AI 对话（转发 Python SSE 流） ----------
        // 1) 用户消息落库（先存，保证"聊天记录完整"）
        chatService.saveMessage(sessionId, "user", dto.getMessage());

        // 2) 建立 SSE 通道：超时给足（真实 LLM 生成可能较慢）
        SseEmitter emitter = new SseEmitter(120_000L);
        emitter.onTimeout(() -> {
            log.warn("SSE 转发超时: session={}", sessionId);
            emitter.complete();
        });

        // 3) WebClient 请求 Python /api/chat/completions，SSE 流原样透传
        webClient.post()
                .uri(aiBaseUrl + "/api/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "message", dto.getMessage(),
                        "user_id", userId,
                        "session_id", sessionId))
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .subscribe(
                        buffer -> sendChunk(emitter, sessionId, buffer),   // 每块原样转发
                        err -> sendErrorAndComplete(emitter, sessionId, err), // 转发失败
                        emitter::complete);                                 // 流结束收尾

        return emitter;
    }

    /**
     * 把一个 DataBuffer（Python 传来的 SSE 数据块）转发给前端。
     *
     * <p>关键点：SseEmitter 对 String 内容会自动编码为 SSE 格式（每行加
     * "data: " 前缀 + 尾部空行），因此这里<b>剥离</b> Python 已加的
     * "data: " 前缀，只把 JSON 内容交给 SseEmitter，避免出现 "data:data: ..."
     * 的双前缀畸形事件。
     *
     * <p>同时做一件"附带的事"：如果这一块是 done 事件（含完整回复），
     * 就把 AI 的完整回复落库到 chat_messages（role=assistant），
     * 保证对话记录完整可回溯。
     */
    private void sendChunk(SseEmitter emitter, Long sessionId, DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer); // 必须释放，否则内存泄漏（Reactor 池化缓冲）
        String chunk = new String(bytes, StandardCharsets.UTF_8);
        // 剥离 "data: " 前缀（Python SSE 事件形如：data: {...}\n\n）
        String jsonContent = chunk;
        if (jsonContent.startsWith("data:")) {
            jsonContent = jsonContent.substring("data:".length()).trim();
        }
        try {
            emitter.send(jsonContent);
            // 顺带解析 done 事件 → 保存 assistant 回复（不阻塞主流程）
            maybeSaveAssistantReply(sessionId, chunk);
            // 顺带解析 done 事件的转人工标签 → AI 主动转人工（阶段 11.6）
            maybeHandleHandoff(sessionId, chunk);
        } catch (Exception e) {
            log.warn("SSE 转发中断: session={}, err={}", sessionId, e.getMessage());
        }
    }

    /**
     * 解析 done 事件中的完整回复并落库。
     *
     * <p>SSE 事件格式：data: {"type":"done","response":"...","handoff":false}\n\n
     * 这里只做轻量判断（匹配 done 且能取出 response），
     * 失败不影响转发（聊天记录非主链路）。
     *
     * <p>关键点：Python 侧 JSON 序列化输出为 {@code "type": "done"}（冒号后带空格），
     * 若用字符串 {@code contains("\"type\":\"done\"")} 精确匹配将永远失败，
     * 导致 assistant 消息从未落库——必须用兼容空格的轻量正则。
     */
    private static final Pattern DONE_EVENT = Pattern.compile("\"type\"\\s*:\\s*\"done\"");
    private static final Pattern RESPONSE_FIELD = Pattern.compile("\"response\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);

    private void maybeSaveAssistantReply(Long sessionId, String chunk) {
        try {
            // 1. 兼容空格的 done 事件判定
            if (!DONE_EVENT.matcher(chunk).find()) {
                return;
            }
            // 2. 提取 response 字段（非贪婪匹配到下一个引号）
            Matcher m = RESPONSE_FIELD.matcher(chunk);
            if (!m.find()) {
                return;
            }
            // 3. JSON 转义反转义（常见转义；复杂场景前端有完整文本兜底）
            String reply = m.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
            chatService.saveMessage(sessionId, "assistant", reply);
            log.info("保存 AI 回复: session={}, len={}", sessionId, reply.length());
        } catch (Exception e) {
            log.debug("解析 done 事件失败（忽略，不影响转发）: {}", e.getMessage());
        }
    }

    /**
     * 解析 done 事件的转人工标签（阶段 11.6）。
     *
     * <p>AI 判断该转人工时（如用户反复追问复杂问题），Python 的 done 事件携带
     * {@code "action": "transfer_to_human"}。Java 端收到后把会话置为排队、
     * 尝试立即分配在线客服，并发布 Redis 通知——用户无需手动点"转人工"。
     */
    private static final Pattern HANDOFF_ACTION = Pattern.compile("\"action\"\\s*:\\s*\"transfer_to_human\"");

    private void maybeHandleHandoff(Long sessionId, String chunk) {
        try {
            if (!HANDOFF_ACTION.matcher(chunk).find()) {
                return;
            }
            chatService.markWaitingHuman(sessionId);
            agentService.assignAgent(sessionId);
            agentService.publishTransfer(sessionId);
            log.info("AI 主动转人工: sessionId={}", sessionId);
        } catch (Exception e) {
            log.debug("解析转人工标签失败（忽略，不影响转发）: {}", e.getMessage());
        }
    }

    /** 转发失败：以 SSE error 事件收尾（前端按统一协议提示），而不是 500 空响应。 */
    private void sendErrorAndComplete(SseEmitter emitter, Long sessionId, Throwable err) {
        log.error("AI 服务转发失败: session={}, err={}", sessionId, err.getMessage());
        try {
            emitter.send("data: {\"type\":\"error\",\"message\":\"AI 客服服务暂时不可用，请稍后再试\"}\n\n");
        } catch (Exception ignored) {
            // 前端可能已断开，忽略
        }
        emitter.complete();
    }

    /** 从 SecurityContext 取当前登录用户 ID（与 CartController 等保持一致）。 */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser current)) {
            throw new BusinessException(BusinessException.UNAUTHORIZED, "未登录或登录已过期");
        }
        return current.userId();
    }
}
