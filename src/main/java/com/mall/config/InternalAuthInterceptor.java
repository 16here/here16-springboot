package com.mall.config;

import com.mall.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 内部接口鉴权拦截器（阶段 11.4）。
 *
 * <p>只拦截 /api/internal/**（由 {@link InternalWebConfig} 注册）。内部接口
 * 供 Python AI 服务（工具调用）访问，鉴权双保险：
 * <ul>
 *   <li><b>内部令牌</b>：请求头 {@code X-Internal-Token} 必须等于
 *       {@code mall.internal.token}（常量时间比较，防时序攻击）；</li>
 *   <li><b>IP 白名单</b>：请求来源 IP 必须在 {@code mall.internal.allowed-ips}
 *       内（默认本机回环）。</li>
 * </ul>
 *
 * <p>安全要点：<b>不信任 X-Forwarded-For</b>——客户端可伪造该头绕过白名单；
 * 只有部署在可信反向代理之后，才应改为取 X-Forwarded-For 首值（本类已注释）。
 */
@Component
public class InternalAuthInterceptor implements HandlerInterceptor {

    /** 内部令牌请求头名（与 Python 端 order_tools 约定一致） */
    private static final String TOKEN_HEADER = "X-Internal-Token";

    @Value("${mall.internal.token:}")
    private String internalToken;

    @Value("${mall.internal.allowed-ips:127.0.0.1}")
    private String allowedIps;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // ① 令牌校验：常量时间比较，避免时序侧信道
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || !constantTimeEquals(token, internalToken)) {
            return reject(response, "内部接口鉴权失败：令牌无效");
        }
        // ② IP 白名单校验
        String ip = resolveClientIp(request);
        Set<String> whitelist = Arrays.stream(allowedIps.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        if (!whitelist.contains(ip)) {
            return reject(response, "内部接口鉴权失败：IP 不在白名单（" + ip + "）");
        }
        return true;
    }

    /** 获取客户端真实 IP。默认只信 Socket 连接的 RemoteAddr（不可伪造）。 */
    private String resolveClientIp(HttpServletRequest request) {
        // 若部署在可信反向代理之后，可改为：
        //   String forwarded = request.getHeader("X-Forwarded-For");
        //   return forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        String remote = request.getRemoteAddr();
        // IPv6 回环的展开写法统一为 ::1，便于白名单匹配
        if ("0:0:0:0:0:0:0:1".equals(remote)) {
            return "::1";
        }
        return remote;
    }

    /** 常量时间字符串比较（防时序攻击；空串直接不相等） */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || b.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    /** 拒绝访问：写 403 + 统一 Result JSON，返回 false 终止请求 */
    private boolean reject(HttpServletResponse response, String message) throws Exception {
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        // 手写与 Result 结构一致的 JSON（不引入 Jackson 编译期依赖，同 SecurityConfig 做法）
        response.getWriter().write(
                "{\"code\":403,\"message\":\"" + message
                        + "\",\"data\":null,\"timestamp\":" + System.currentTimeMillis() + "}");
        return false;
    }
}
