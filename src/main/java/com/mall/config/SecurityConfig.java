package com.mall.config;

import com.mall.common.Result;
import com.mall.middleware.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/**
 * Spring Security 配置。
 *
 * <p>阶段 0 采用无状态 JWT 认证：
 * <ul>
 *   <li>关闭 CSRF 与 Session（前后端分离、token 认证下不需要）</li>
 *   <li>放行：注册/登录等认证接口、商品查询接口</li>
 *   <li>保护：用户中心、购物车、订单接口（需携带有效 token）</li>
 *   <li>在用户名密码过滤器之前插入 {@link JwtAuthFilter} 完成 token 校验</li>
 *   <li>未认证/无权限时返回统一 JSON（{@link Result} 结构），而不是重定向到登录页</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 密码编码器：BCrypt 单向哈希，注册/登录时使用。
     * 注：明文密码永不落库、永不写日志。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 用户明细服务（空实现）。
     *
     * <p>本系统采用无状态 JWT 认证，不依赖 Spring Security 的
     * UserDetailsService 机制。显式声明该 Bean 只是为了让 Spring Boot
     * 的 {@code UserDetailsServiceAutoConfiguration} 因"已存在 Bean"而
     * 自动退避，避免启动日志出现无关的 generated security password 提示。
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return new UserDetailsService() {
            @Override
            public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
                throw new UsernameNotFoundException("JWT 认证模式下无需使用该登录方式");
            }
        };
    }

    /**
     * 安全过滤链：定义接口放行与保护规则。
     *
     * @param http           HttpSecurity（由 Spring Security 自动注入）
     * @param jwtAuthFilter  自定义 JWT 过滤器
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        http
                // 前后端分离架构：关闭 CSRF 与跨站伪造防护
                .csrf(AbstractHttpConfigurer::disable)
                // 启用 CORS（具体规则见 CorsConfig）
                .cors(Customizer.withDefaults())
                // 无状态：不创建也不使用 Session，每次请求靠 token 认证
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 接口访问规则
                .authorizeHttpRequests(auth -> auth
                        // 认证相关接口全部放行（注册、登录、登出）
                        .requestMatchers("/api/auth/**").permitAll()
                        // 商品查询接口（GET）放行；商品管理接口（/api/admin/**）受保护
                        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        // SKU 实时库存查询接口（GET，阶段 6）：商品详情页公开展示，无需登录
                        .requestMatchers(HttpMethod.GET, "/api/skus/**").permitAll()
                        // 分类查询接口（GET）放行；分类管理接口（/api/admin/**）受保护
                        .requestMatchers(HttpMethod.GET, "/api/categories/**").permitAll()
                        // 商品评价查询接口（GET，阶段 7）：商品详情页评价列表/评分统计公开，无需登录
                        .requestMatchers(HttpMethod.GET, "/api/reviews/**").permitAll()
                        // 领券中心列表（GET /api/coupons/center，阶段 8）：未登录也能逛领券中心；
                        // 领取/我的券/可用券（POST/GET /api/coupons/** 其余路径）仍需登录
                        .requestMatchers(HttpMethod.GET, "/api/coupons/center").permitAll()
                        // 推荐接口（GET，阶段 10）：未登录访问首页/商品详情推荐位时
                        // 返回热度推荐（公共内容），已登录由服务层自动给个性化
                        .requestMatchers(HttpMethod.GET, "/api/recommend/**").permitAll()
                        // 健康检查：运维探活需要无认证可访问
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        // 支付回调：支付平台异步通知，无法携带用户 token，必须放行
                        .requestMatchers("/api/pay/callback/**").permitAll()
                        // AI 客服内部接口（阶段 11.4）：不走用户 JWT，
                        // 鉴权由 InternalAuthInterceptor 完成（X-Internal-Token + IP 白名单）
                        .requestMatchers("/api/internal/**").permitAll()
                        // Spring Boot 错误转发路径，避免错误页被 401 拦截
                        .requestMatchers("/error").permitAll()
                        // 用户中心、购物车、订单、收货地址、支付（回调除外）、评价发表、售后、物流、收藏夹：必须登录
                        .requestMatchers("/api/users/**", "/api/cart/**", "/api/orders/**", "/api/addresses/**",
                                "/api/pay/**", "/api/reviews/**", "/api/after-sales/**",
                                "/api/coupons/**", "/api/shipping/**", "/api/favorites/**").authenticated()
                        // 客服对话（阶段 11.5）：聊天窗悬浮全站，但发送消息必须登录
                        // （会话与聊天记录按用户归档，未登录无法建立会话）
                        .requestMatchers("/api/chat/**").authenticated()
                        // 商品/分类/订单/支付管理接口：阶段 5 起要求 ADMIN 角色
                        // （JwtAuthFilter 已按 token 中的 role 生成 ROLE_<role> 权限；
                        //  普通用户访问返回 403 无权访问）
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 默认策略：未显式配置的一律要求认证（避免新增接口意外裸奔）
                        .anyRequest().authenticated())
                // 未认证（401）与无权限（403）时返回统一 JSON
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.getWriter().write(buildErrorJson(401, "未登录或登录已过期"));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.getWriter().write(buildErrorJson(403, "无权访问该资源"));
                        }))
                // 在 Spring Security 内置的用户名密码过滤器之前执行 JWT 校验
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 构造与 {@link Result} 结构一致的 JSON 字符串。
     *
     * <p>此处不依赖 JSON 序列化库，直接拼固定结构，避免版本迁移带来的依赖问题。
     */
    private static String buildErrorJson(int code, String message) {
        return "{\"code\":" + code
                + ",\"message\":\"" + message
                + "\",\"data\":null,\"timestamp\":" + System.currentTimeMillis() + "}";
    }
}
