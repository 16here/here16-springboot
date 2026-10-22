package com.mall.config;

import com.mall.common.ShardingContext;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 分表路由上下文清理过滤器（Phase 3 分表）。
 *
 * <p>每个 HTTP 请求进入时先清空分表路由（防止上一次请求残留的 ThreadLocal
 * 串号到本次请求），请求结束（filterChain 返回后）再清一次（正常结束兜底）。
 *
 * <p>为什么必须按"请求"粒度清理：分表路由（如 OrderService.create 里设置的
 * orders_0）需要在一个请求的多条 SQL 间保持（下单 → 写订单 → 写明细 → 锁库存
 * 是同一次请求），但 Tomcat 线程池会复用线程，若不清理，下一个请求会继承
 * 上一个请求的路由，导致 SQL 写错分表。这里统一在请求边界清理，业务代码
 * 无需关心清理时机。
 */
@Component
public class ShardingContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            // 请求开始先清一次：线程池复用线程时，保证本请求从干净状态开始
            ShardingContext.clear();
            filterChain.doFilter(request, response);
        } finally {
            // 请求结束必清：防止路由泄漏到下一个请求
            ShardingContext.clear();
        }
    }
}
