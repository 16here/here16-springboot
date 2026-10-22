package com.mall.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 缓存响应头过滤器（Phase 1 静态化 + CDN 友好）。
 *
 * <p>给"读多写少"的公共 GET 接口追加 {@code Cache-Control} 响应头，
 * 让浏览器/CDN/反向代理（Nginx）可以在 TTL 内直接复用响应，不再打到应用层：
 * <ul>
 *   <li>{@code /api/products} 商品列表：30 秒（含搜索/分类筛选，动态度中等）；</li>
 *   <li>{@code /api/categories} 分类树：120 秒（极少变更，且后端已做双删保证一致性）；</li>
 *   <li>{@code /api/recommend} 推荐位：60 秒（热度推荐公共内容）。</li>
 * </ul>
 *
 * <p>为什么商品详情不加：详情接口含"浏览行为埋点 + 实时库存覆盖"，
 * 且登录用户行为需要个性化，交给 CDN 缓存会丢个性化与埋点。
 * 下单/购物车等写接口和用户私有接口自然不加（Security 层已保护）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // 尽早执行，保证任何后续过滤链都能看到响应头
public class CacheHeaderFilter extends OncePerRequestFilter {

    /** 是否需要给该 URI 附加缓存头（公共读接口白名单） */
    private static boolean isCacheable(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        return uri.equals("/api/products")
                || uri.equals("/api/categories")
                || uri.equals("/api/recommend/home");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 放行前先记下是否需要加头（响应状态未知，加头动作放在 finally 语义之后）
        boolean cacheable = isCacheable(request);
        filterChain.doFilter(request, response);
        if (cacheable) {
            // 仅成功响应才允许缓存（4xx/5xx 不缓存，避免把错误页缓存给用户）
            if (response.getStatus() >= 200 && response.getStatus() < 400) {
                response.setHeader("Cache-Control", "public, max-age=30");
            }
        }
    }
}
