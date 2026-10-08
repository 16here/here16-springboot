package com.mall.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户实体，对应表 users。
 *
 * <p>注意：password 字段只在本类内部流转（落库为 BCrypt 密文），
 * 任何返回给前端的 VO 都不得包含该字段。
 */
@Data
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID（自增主键） */
    private Long id;

    /** 用户名（唯一登录标识） */
    private String username;

    /** 密码（BCrypt 密文，60 位定长） */
    private String password;

    /** 手机号（唯一，可用于登录） */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 头像地址 */
    private String avatar;

    /** 账号状态：1=正常，0=禁用 */
    private Integer status;

    /** 角色：USER=普通用户，ADMIN=管理员（阶段 5 起用于管理端接口权限控制） */
    private String role;

    /** 注册时间 */
    private LocalDateTime createdAt;
}
