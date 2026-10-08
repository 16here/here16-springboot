package com.mall.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类。
 *
 * <p>负责 token 的生成、解析与过期判断。token 中承载：
 * <ul>
 *   <li>subject：用户 ID（字符串形式）</li>
 *   <li>claim username：用户名（便于前端展示，避免频繁查库）</li>
 *   <li>iat / exp：签发时间 / 过期时间</li>
 * </ul>
 *
 * <p>密钥通过配置项 {@code jwt.secret} 注入，长度必须不小于 32 字节
 * （HS256 算法要求），生产环境务必更换为随机生成的长密钥。
 */
@Slf4j
@Component
public class JwtUtil {

    /** HS256 签名密钥（由 application.yml 注入） */
    @Value("${jwt.secret}")
    private String secret;

    /** token 有效期（毫秒，由 application.yml 注入） */
    @Value("${jwt.expiration}")
    private Long expiration;

    /** 根据 secret 构建的签名密钥对象，初始化一次复用 */
    private SecretKey key;

    /** 组件初始化后构建密钥（依赖注入完成后执行） */
    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 JWT token。
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @param role     角色（USER / ADMIN，阶段 5 起放入 token 供鉴权使用）
     * @return 签发的 token 字符串
     */
    public String generateToken(Long userId, String username, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiration);
        return Jwts.builder()
                .subject(String.valueOf(userId))       // 主体：用户 ID
                .claim("username", username)            // 附加：用户名
                .claim("role", role == null ? "USER" : role)  // 附加：角色（阶段 5）
                .issuedAt(now)                          // 签发时间
                .expiration(expiry)                     // 过期时间
                .signWith(key)                          // HS256 签名
                .compact();
    }

    /**
     * 解析 token 并返回 Claims（载荷）。
     *
     * @param token 待解析的 token
     * @return Claims；token 非法、签名错误或已过期时返回 null
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)        // 使用相同密钥校验签名
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.warn("token 已过期");
            return null;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("token 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 判断 token 是否已过期。
     *
     * @param token 待检查的 token
     * @return true=已过期或非法，false=仍有效
     */
    public boolean isExpired(String token) {
        Claims claims = parseToken(token);
        return claims == null || claims.getExpiration() == null
                || claims.getExpiration().before(new Date());
    }

    /**
     * 从 token 中提取用户 ID。
     *
     * @param token 有效 token
     * @return 用户 ID；解析失败返回 null
     */
    public Long getUserId(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return null;
        }
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从 token 中提取用户名。
     */
    public String getUsername(String token) {
        Claims claims = parseToken(token);
        return claims == null ? null : claims.get("username", String.class);
    }
}
