package com.mall.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * WebClient 配置（阶段 11.5）。
 *
 * <p>职责：显式声明 WebClient Bean，供 ChatController 转发 Python AI 服务的
 * SSE 流使用。
 *
 * <p>为什么显式声明而不是用 Spring Boot 自动配置的 WebClient.Builder：
 * <ul>
 *   <li>本应用是 Spring MVC（servlet）应用，Spring Boot 4 的 WebFlux 自动配置
 *       在 servlet 应用下不再自动注册 WebClient（此前启动报
 *       "required a bean of type 'WebClient'"）；</li>
 *   <li>显式声明还能顺带配置连接超时 / 响应超时（从 application.yml 读取），
 *       避免转发慢速 LLM 流时被默认超时掐断。</li>
 * </ul>
 */
@Configuration
public class WebClientConfig {

    /**
     * 转发用 WebClient（Reactor Netty 传输层）。
     *
     * @param connectTimeoutMs 连接超时（yml: mall.ai-service.connect-timeout-ms）
     * @param readTimeoutMs    响应/读取超时（yml: mall.ai-service.read-timeout-ms）
     */
    @Bean
    public WebClient aiWebClient(
            @Value("${mall.ai-service.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${mall.ai-service.read-timeout-ms:120000}") long readTimeoutMs) {
        HttpClient httpClient = HttpClient.create()
                // 建连超时（毫秒）
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeoutMs)
                // 整个响应（SSE 流）的读取超时；流结束即结束，不主动掐断
                .responseTimeout(Duration.ofMillis(readTimeoutMs));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
