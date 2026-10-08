package com.mall.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册内部接口鉴权拦截器（阶段 11.4）。
 *
 * <p>只对 /api/internal/** 生效——该路径已在 {@link SecurityConfig} 放行
 * （不要求用户 JWT），鉴权完全交给 {@link InternalAuthInterceptor}
 * （内部令牌 + IP 白名单），实现"内部接口独立鉴权、不依赖用户登录态"。
 */
@Configuration
@RequiredArgsConstructor
public class InternalWebConfig implements WebMvcConfigurer {

    private final InternalAuthInterceptor internalAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalAuthInterceptor)
                .addPathPatterns("/api/internal/**");
    }
}
