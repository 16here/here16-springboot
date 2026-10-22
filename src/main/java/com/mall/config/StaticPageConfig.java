package com.mall.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 静态页资源映射（Phase 1 静态化）。
 *
 * <p>把 {@code ./static-page/} 目录（相对后端运行目录）映射为
 * {@code /static-page/**} 静态资源：定时任务生成的静态 JSON/HTML
 * 可以直接通过 {@code http://localhost:8080/static-page/home.json} 访问。
 *
 * <p>落地部署时的用法：把这目录交给 Nginx/CDN 直接托管（alias 指向它），
 * 静态文件完全不走 Java 进程；本地开发则走本映射便于验证。
 */
@Configuration
public class StaticPageConfig implements WebMvcConfigurer {

    /** 静态页根目录（相对后端运行目录；与 StaticPageService 写文件路径保持一致） */
    private static final String STATIC_PAGE_DIR = "./static-page/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(STATIC_PAGE_DIR).toAbsolutePath().normalize();
        registry.addResourceHandler("/static-page/**")
                // file: 协议前缀是 Spring 静态资源映射的要求（指向本地磁盘目录）
                .addResourceLocations("file:" + dir + "/");
    }
}
