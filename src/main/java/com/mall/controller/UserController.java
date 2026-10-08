package com.mall.controller;

import com.mall.common.CurrentUser;
import com.mall.common.Result;
import com.mall.dto.UpdateProfileDTO;
import com.mall.dto.UserVO;
import com.mall.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户中心接口：个人资料查询与修改。
 *
 * <p>本模块路径 /api/users/** 已在 SecurityConfig 中要求登录，
 * 因此这里的"当前用户"一定来自已认证的 SecurityContext。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 查询个人资料 */
    @GetMapping("/profile")
    public Result<UserVO> getProfile() {
        return Result.success(userService.getProfile(currentUserId()));
    }

    /** 修改个人资料（邮箱 / 头像 / 手机号） */
    @PutMapping("/profile")
    public Result<UserVO> updateProfile(@Valid @RequestBody UpdateProfileDTO dto) {
        return Result.success(userService.updateProfile(currentUserId(), dto));
    }

    /**
     * 从 SecurityContext 提取当前登录用户 ID。
     * principal 类型为 {@link CurrentUser}（见 JwtAuthFilter）。
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser current) {
            return current.userId();
        }
        // 走到这里说明 token 校验失败但请求仍被放行（正常不会发生），防御性兜底
        throw new com.mall.common.BusinessException(
                com.mall.common.BusinessException.UNAUTHORIZED, "登录状态已失效，请重新登录");
    }
}
