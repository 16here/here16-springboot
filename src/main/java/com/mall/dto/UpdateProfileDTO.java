package com.mall.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改个人资料请求参数。
 *
 * <p>用户名作为登录标识不允许修改；手机号允许修改但需保持全局唯一。
 */
@Data
public class UpdateProfileDTO {

    /** 邮箱（可选） */
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 头像地址（可选） */
    @Size(max = 255, message = "头像地址过长")
    private String avatar;

    /** 手机号（可选，11 位大陆手机号） */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}
