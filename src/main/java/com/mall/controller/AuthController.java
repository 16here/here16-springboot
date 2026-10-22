package com.mall.controller;

import com.mall.common.BusinessException;
import com.mall.common.CurrentUser;
import com.mall.common.Result;
import com.mall.dto.LoginDTO;
import com.mall.dto.LoginResult;
import com.mall.dto.RegisterDTO;
import com.mall.dto.UserVO;
import com.mall.service.AuthService;
import com.mall.service.CaptchaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口：注册、登录、登出、当前登录用户。
 *
 * <p>本模块路径 /api/auth/** 已在 SecurityConfig 中全部放行
 * （登出接口本身不做权限校验，token 失效与否由过滤器决定）。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;

    /** 获取登录图形验证码（Phase 1：返回 captchaId + Base64 图片，答案只存 Redis） */
    @GetMapping("/captcha")
    public Result<Map<String, String>> captcha() {
        return Result.success(captchaService.generate());
    }

    /** 用户注册 */
    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.success(authService.register(dto));
    }

    /** 用户登录（支持用户名或手机号） */
    @PostMapping("/login")
    public Result<LoginResult> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success(authService.login(dto));
    }

    /**
     * 用户登出：删除 Redis 白名单中的 token。
     *
     * @param authHeader 请求头 Authorization（Bearer xxx），可能为空
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        // 剥离 "Bearer " 前缀后交给 Service 删除
        String token = authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring(7)
                : null;
        authService.logout(token);
        return Result.success();
    }

    /** 获取当前登录用户信息（供前端刷新页面后恢复登录态） */
    @GetMapping("/me")
    public Result<UserVO> me() {
        // /api/auth/** 在 Security 中整体放行，这里需自行校验登录态
        CurrentUser current = currentUser();
        if (current == null) {
            throw new BusinessException(BusinessException.UNAUTHORIZED, "未登录或登录已过期");
        }
        return Result.success(authService.me(current.userId()));
    }

    /**
     * 从 Spring Security 上下文提取当前登录用户。
     * 该上下文由 JwtAuthFilter 在 token 校验通过后写入。
     */
    private CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser current)) {
            return null;
        }
        return current;
    }
}
