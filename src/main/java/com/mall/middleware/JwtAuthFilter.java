package com.mall.middleware;

import com.mall.common.CurrentUser;
import com.mall.common.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器。
 *
 * <p>职责：
 * <ol>
 *   <li>从请求头 {@code Authorization: Bearer <token>} 中提取 token</li>
 *   <li>校验 token 签名与过期时间（{@link JwtUtil}）</li>
 *   <li>校验 Redis 白名单：登录时写入、登出时删除，保证"登出即失效"</li>
 *   <li>校验通过后把 {@link CurrentUser} 写入 SecurityContext，放行请求</li>
 * </ol>
 *
 * <p>任何一步失败都不在此抛异常，而是直接放行，由 Spring Security 的
 * 认证入口统一返回 401 JSON（见 SecurityConfig）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Redis 中 token 白名单的 key 前缀 */
    private static final String TOKEN_KEY_PREFIX = "auth:token:";

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;

    /** 请求头名称，如 Authorization */
    @Value("${jwt.header}")
    private String header;

    /** token 前缀，如 "Bearer " */
    @Value("${jwt.prefix}")
    private String prefix;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 1. 提取请求头中的 token
        String token = resolveToken(request);

        // 2. 有 token 且当前上下文尚未认证时，尝试认证
        if (StringUtils.hasText(token)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(token);
        }

        // 3. 无论认证成功与否都继续过滤链，由 Security 决定是否放行
        filterChain.doFilter(request, response);
    }

    /**
     * 允许 async dispatch 重新执行本过滤器（阶段 11.5 关键修复）。
     *
     * <p>背景：SseEmitter（SSE 流式推送）依赖 Servlet 异步机制——Tomcat 在
     * 异步响应完成时会触发一次 async dispatch，重跑安全过滤链。
     * OncePerRequestFilter 默认 {@code shouldNotFilterAsyncDispatch()==true}，
     * 即异步阶段会跳过本过滤器，导致 SecurityContext 为空，
     * AuthorizationFilter 判定"未认证"抛 AccessDenied；而此刻响应已提交，
     * 无法再返回 401/403，连接被异常中断（前端表现为流收不到、请求挂起）。
     *
     * <p>修复：返回 false 让异步阶段也执行本过滤器，重新解析请求头 token
     * 并写入 SecurityContext，异步阶段的授权检查即可通过。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /**
     * 从请求头提取 Bearer token。
     *
     * @return 去前缀后的 token；无 token 或格式不符返回 null
     */
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(header);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(prefix)) {
            return bearerToken.substring(prefix.length());
        }
        return null;
    }

    /**
     * 校验 token 并写入 SecurityContext。
     */
    private void authenticate(String token) {
        // 2.1 签名 + 过期校验
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            return;
        }
        // 2.2 Redis 白名单校验：登出后 key 被删除，token 立即失效
        String userIdInToken = claims.getSubject();
        Boolean exists = stringRedisTemplate.hasKey(TOKEN_KEY_PREFIX + token);
        if (Boolean.FALSE.equals(exists)) {
            log.debug("token 不在白名单，视为已登出: userId={}", userIdInToken);
            return;
        }

        // 2.3 构造认证对象并写入上下文
        //     阶段 5：从 token 中解析角色（USER/ADMIN），
        //     生成 ROLE_<role> 权限供 SecurityConfig 的 hasRole("ADMIN") 判定
        Long userId = Long.valueOf(userIdInToken);
        String username = claims.get("username", String.class);
        String role = claims.get("role", String.class);
        if (role == null || role.isBlank()) {
            role = "USER"; // 兼容旧 token：未携带角色按普通用户处理
        }
        CurrentUser currentUser = new CurrentUser(userId, username, role);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        currentUser,                        // principal：业务可直接取用的当前用户
                        null,                               // credentials：JWT 场景不需要
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        log.debug("JWT 认证成功: userId={}, role={}", userId, role);
    }
}
