package com.mall.common;

/**
 * 当前登录用户信息。
 *
 * <p>由 {@code JwtAuthFilter} 解析 token 后写入 Spring Security 上下文，
 * 业务代码（Service / Controller）无需再解析 token，直接从这里取
 * 当前用户即可。后续阶段如需角色（如管理员、运营），在此扩展字段。
 *
 * @param userId   用户 ID
 * @param username 用户名
 * @param role     角色（阶段 0 固定 ROLE_USER，后续阶段扩展）
 */
public record CurrentUser(Long userId, String username, String role) {
}
