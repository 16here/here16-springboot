package com.mall.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应结果封装。
 *
 * <p>所有接口均返回该结构，前端依据 {@code code} 判断业务是否成功：
 * <ul>
 *   <li>200：成功</li>
 *   <li>400：参数错误 / 业务校验失败</li>
 *   <li>401：未认证或 token 失效</li>
 *   <li>403：无权限</li>
 *   <li>404：资源不存在</li>
 *   <li>500：服务端异常</li>
 * </ul>
 *
 * @param <T> data 数据类型
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务状态码，200 表示成功 */
    private Integer code;

    /** 提示信息 */
    private String message;

    /** 业务数据，可为 null */
    private T data;

    /** 服务端响应时间戳（毫秒） */
    private Long timestamp;

    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    /** 成功响应，无数据 */
    public static <T> Result<T> success() {
        return new Result<>(200, "success", null);
    }

    /** 成功响应，携带数据 */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data);
    }

    /** 失败响应，携带自定义状态码与信息 */
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    /** 失败响应，默认 500 */
    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null);
    }
}
