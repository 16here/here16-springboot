package com.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 客服对话请求 DTO（/api/chat/message）。
 *
 * <p>前端聊天窗发送一条消息给 Java 后端：
 * <ul>
 *   <li>type=AI（默认）：Java 转发 Python AI 服务，SSE 流透传回前端</li>
 *   <li>type=HUMAN：用户点击"转人工"，Java 将会话标记为等待人工，直接返回提示</li>
 * </ul>
 */
@Data
public class ChatMessageDTO {

    /** 消息内容（非空，最长 2000 字） */
    @NotBlank(message = "消息不能为空")
    @Size(max = 2000, message = "消息过长")
    private String message;

    /** 消息类型：AI（默认）/ HUMAN（转人工）。大小写不敏感。 */
    private String type = "AI";
}
