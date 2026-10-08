package com.mall.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户信息视图对象（返回给前端的用户数据）。
 *
 * <p>刻意不包含 password 字段，避免敏感信息外泄。
 */
@Data
public class UserVO {

    /** 用户 ID */
    private Long id;

    /** 用户名 */
    private String username;

    /** 手机号 */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 头像地址 */
    private String avatar;

    /** 账号状态：1=正常，0=禁用 */
    private Integer status;

    /** 角色：USER=普通用户，ADMIN=管理员（管理端登录后可据此展示身份） */
    private String role;

    /** 注册时间 */
    private LocalDateTime createdAt;
}
