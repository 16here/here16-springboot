package com.mall.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求参数。
 */
@Data
public class LoginDTO {

    /** 登录账号：支持用户名或手机号 */
    @NotBlank(message = "账号不能为空")
    private String account;

    /** 密码 */
    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 图形验证码 ID（Phase 1：登录前先调 GET /api/auth/captcha 获取；
     * 与 captchaCode 配套，服务端 Redis 比对后一次性消耗）。
     * 不设 @NotBlank：验证码开关关闭（测试/联调）时允许不传。
     */
    private String captchaId;

    /** 用户输入的验证码（4 位字符，大小写不敏感） */
    private String captchaCode;
}
