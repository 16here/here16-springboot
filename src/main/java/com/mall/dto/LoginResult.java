package com.mall.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录成功返回体：token + 用户信息。
 */
@Data
@AllArgsConstructor
public class LoginResult {

    /** JWT token，前端需在后续请求的 Authorization 头携带 */
    private String token;

    /** 当前登录用户信息 */
    private UserVO user;
}
