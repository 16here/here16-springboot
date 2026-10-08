package com.mall.common;

/**
 * 业务异常。
 *
 * <p>业务代码中主动抛出，由 {@link GlobalExceptionHandler} 统一拦截并
 * 转换为 {@link Result} 返回给前端。相比直接返回错误 Result，
 * 抛出异常可以让 Service 层的校验逻辑更简洁、语义更清晰。
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 常用错误码 */
    public static final int PARAM_ERROR = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int SERVER_ERROR = 500;

    /** 业务错误码（非 HTTP 码，便于前端按码精确处理，如 2001=用户名已存在） */
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** 默认按参数错误（400）处理 */
    public BusinessException(String message) {
        this(PARAM_ERROR, message);
    }

    public int getCode() {
        return code;
    }
}
