package com.mall.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 服务自动拉起与回收管理器。
 *
 * <p>【是什么】监听 Spring 生命周期事件：应用启动就绪后，如果 8100 端口没有
 * Python AI 服务在监听，就用 {@link ProcessBuilder} 启动 ai-service 的 uvicorn；
 * 应用关闭时，把拉起的进程一并回收，避免留下孤儿进程占用端口。
 *
 * <p>【干什么】
 * <ol>
 *   <li>监听 {@link ApplicationReadyEvent}（应用完全就绪后触发）：异步拉起 AI 服务；</li>
 *   <li>端口探测：8100 已被占用说明有人（或上次会话）已启动，直接跳过，幂等安全；</li>
 *   <li>就绪轮询：每隔 1 秒探测端口，直到 AI 服务能响应或超时（超时只告警、不影响后端）；</li>
 *   <li>关闭回收：{@link PreDestroy} + JVM 关闭钩子双保险，销毁 uvicorn 进程。</li>
 * </ol>
 *
 * <p>【为什么】AI 客服是 Java 后端转发到 Python 的跨进程架构，手工先起 AI 再起后端
 * 容易忘、容易错。让后端负责拉起与回收，开发时只需启动后端一个命令；
 * 用独立 daemon 线程执行，避免等待 AI 服务（加载模型可能 30~90 秒）阻塞后端主线程。
 *
 * <p>【关键点】
 * <ul>
 *   <li>日志重定向：子进程 stdout/stderr 必须重定向到文件，否则管道不读会满、进程卡死；</li>
 *   <li>关闭钩子幂等：{@link PreDestroy} 与 shutdown hook 都可能触发回收，
 *       用 {@link AtomicBoolean} 保证只执行一次；</li>
 *   <li>端口被其他程序占用时也视为"已在运行"而跳过，符合"谁占用谁负责"的约定。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiServiceAutoStartConfig {

    /** 自动拉起配置（绑定 application.yml 的 mall.ai-service 段） */
    private final AiServiceProperties props;

    /** 被本管理器拉起的 uvicorn 进程（volatile：关闭钩子线程可见） */
    private volatile Process aiProcess;

    /** 回收动作是否已执行（避免 PreDestroy 与 shutdown hook 重复销毁） */
    private final AtomicBoolean shutdownDone = new AtomicBoolean(false);

    /** 异步启动线程池：daemon 线程，不阻塞后端主线程，也不阻止 JVM 退出 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-service-autostart");
        t.setDaemon(true);
        return t;
    });

    /**
     * 应用启动就绪后自动拉起 AI 服务（异步执行，不阻塞应用）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!props.isAutoStartEnabled()) {
            log.info("AI 服务自动启动已关闭（mall.ai-service.auto-start-enabled=false），跳过");
            return;
        }
        executor.submit(this::startAiService);
    }

    /**
     * 拉起 AI 服务主逻辑：
     * 1) 端口探测，已在运行则跳过；2) 启动 uvicorn 子进程；3) 轮询等待就绪。
     */
    private void startAiService() {
        try {
            if (isPortInUse(props.getPort())) {
                log.info("AI 服务已在端口 {} 运行，跳过自动启动", props.getPort());
                return;
            }
            // 组装命令：python -m uvicorn app.main:app --host 127.0.0.1 --port 8100
            List<String> cmd = List.of(
                    props.getPythonPath(),
                    "-m", "uvicorn",
                    "app.main:app",
                    "--host", "127.0.0.1",
                    "--port", String.valueOf(props.getPort()));
            Path workingDir = Paths.get(props.getWorkingDir());
            Path logPath = Paths.get(props.getLogFile());
            Files.createDirectories(logPath.getParent());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workingDir.toFile());
            // 子进程输出合并重定向到日志文件，防止管道缓冲区写满导致进程挂起
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
            // 限制 OpenBLAS 线程数：onnxruntime/numpy 加载时 OpenBLAS 默认按 CPU 核数
            // 申请线程内存缓冲，内存不足时抛 "OpenBLAS error: Memory allocation failed"
            // 导致 AI 服务启动失败（开发机内存被 Gradle/Docker/IDE 占用时尤其常见）。
            // 设 2 线程对单请求推理（毫秒级）无感知，但能显著降低峰值内存。
            pb.environment().put("OPENBLAS_NUM_THREADS", "2");
            pb.environment().put("OMP_NUM_THREADS", "2");

            log.info("开始自动启动 AI 服务：{}（工作目录：{}，日志：{}）", cmd, props.getWorkingDir(), props.getLogFile());
            aiProcess = pb.start();

            // 轮询端口：最多等 startup-timeout-ms，超时只告警不抛异常（不影响后端可用性）
            long deadline = System.currentTimeMillis() + props.getStartupTimeoutMs();
            while (System.currentTimeMillis() < deadline) {
                if (isPortInUse(props.getPort())) {
                    log.info("AI 服务已就绪：http://127.0.0.1:{}", props.getPort());
                    return;
                }
                if (!aiProcess.isAlive()) {
                    log.error("AI 服务进程提前退出（{}ms 内），详见日志：{}", props.getStartupTimeoutMs(), props.getLogFile());
                    return;
                }
                Thread.sleep(1000);
            }
            log.warn("AI 服务在 {}ms 内未就绪（可能仍在加载模型），后端不受影响，可稍后访问 8100", props.getStartupTimeoutMs());
        } catch (Exception e) {
            // 自动拉起失败绝不能让后端崩溃：记录日志，由人工手动启动 AI 服务
            log.error("AI 服务自动启动失败（可手动运行 uvicorn 启动）：{}", e.getMessage(), e);
        }
    }

    /**
     * 探测端口是否被占用。
     *
     * @return true=端口已被占用（服务已在运行）；false=端口空闲（需要启动）
     */
    private boolean isPortInUse(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * 注册 JVM 关闭钩子：即使应用被强杀（KILL 等不触发 @PreDestroy 的场景），
     * 也能回收 AI 服务进程，避免端口残留占用。
     */
    @PostConstruct
    public void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownAiService, "ai-service-shutdown-hook"));
    }

    /**
     * 应用正常关闭时回收 AI 服务进程（与关闭钩子互斥幂等）。
     */
    @PreDestroy
    public void onShutdown() {
        shutdownAiService();
        executor.shutdownNow();
    }

    /**
     * 回收 AI 服务进程：先优雅终止，5 秒后仍存活则强制终止。
     * 幂等：用 AtomicBoolean 保证整个 JVM 生命周期内只执行一次。
     */
    private void shutdownAiService() {
        Process process = aiProcess;
        if (process == null || !shutdownDone.compareAndSet(false, true)) {
            return;
        }
        log.info("后端关闭，回收 AI 服务进程...");
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("AI 服务 5 秒内未退出，强制终止");
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
