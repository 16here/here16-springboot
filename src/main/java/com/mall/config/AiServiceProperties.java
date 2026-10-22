package com.mall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 服务自动启动配置（阶段 11 运维增强）。
 *
 * <p>将 application.yml 中 {@code mall.ai-service} 段的配置绑定到本类，
 * 供 {@link AiServiceAutoStartConfig} 使用。把路径/开关全部外置到配置文件，
 * 是为了避免把本机路径写死在代码里——换机器或部署到服务器时只改 yml 即可。
 *
 * <p>【是什么】AI 客服体系由 Java 后端（8080）转发到 Python AI 服务（8100）。
 * 为了"启动后端即启动 AI 服务"，后端需要在启动就绪后自动拉起 uvicorn 进程，
 * 这些被拉起的进程如何启动、工作目录在哪、等多久算就绪，都由本类配置。
 *
 * <p>【关键点】
 * <ul>
 *   <li>前缀 {@code mall.ai-service} 与既有 AI 转发配置（base-url 等）同段，
 *       便于维护时一眼看全 AI 相关配置；</li>
 *   <li>{@code enabled=false} 可一键关闭自动拉起（比如用 IDE 手动起 AI 服务调试时）；</li>
 *   <li>路径使用正斜杠，Windows 下同样有效，避免 YAML 反斜杠转义问题。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.ai-service")
public class AiServiceProperties {

    /** 是否在后端启动时自动拉起 AI 服务（false = 关闭自动启动，手动管理） */
    private boolean autoStartEnabled = true;

    /** Python 解释器路径（ai-service 项目的虚拟环境，正斜杠路径） */
    private String pythonPath = "D:/myproject/here16/here16-ai/ai-service/.venv/Scripts/python.exe";

    /** 工作目录：uvicorn 必须在此目录下启动，才能 import 到 app 包 */
    private String workingDir = "D:/myproject/here16/here16-ai/ai-service";

    /** AI 服务监听端口（用于探测是否已在运行、等待就绪） */
    private int port = 8100;

    /** 等待 AI 服务就绪的超时时间（毫秒）。ai-service 启动时要加载 ONNX 模型，给足时间 */
    private long startupTimeoutMs = 90000;

    /** 子进程 stdout/stderr 重定向日志文件（相对后端运行目录） */
    private String logFile = "logs/ai-service.log";
}
