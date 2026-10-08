package com.mall.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 跨域配置。
 *
 * <p>前后端分离部署（Vue 开发服务器与后端端口不同），需要允许浏览器
 * 跨域携带凭证（Cookie、Authorization 头）访问后端接口。
 *
 * <p>开发期放开本地任意端口；生产环境请按实际域名收紧
 * {@code setAllowedOriginPatterns}。
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // 允许来源：开发期前端 Vite 端口不固定，放开 localhost 任意端口
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        // 允许的 HTTP 方法
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // 允许携带的请求头（Authorization、Content-Type 等）
        config.setAllowedHeaders(List.of("*"));
        // 是否允许携带 Cookie 等凭证
        config.setAllowCredentials(true);
        // 预检请求缓存 1 小时，减少 OPTIONS 请求
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 对所有路径生效
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
