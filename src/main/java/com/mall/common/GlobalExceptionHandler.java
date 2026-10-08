package com.mall.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常拦截器。
 *
 * <p>统一将各类异常转换为 {@link Result} 结构返回，避免异常堆栈直接
 * 暴露给前端。各异常的处理顺序由 Spring 按"最具体优先"自动选择。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：直接返回业务错误码与提示信息。
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验异常（@RequestBody 上使用 @Valid 触发）。
     * 取第一条校验失败的提示信息返回，避免把整段错误堆到前端。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", message);
        return Result.error(BusinessException.PARAM_ERROR, message);
    }

    /**
     * 表单绑定异常（非 JSON 请求体校验失败时触发）。
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("参数绑定失败");
        return Result.error(BusinessException.PARAM_ERROR, message);
    }

    /**
     * 请求体无法解析（如 JSON 格式错误、缺少请求体）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败", e);
        return Result.error(BusinessException.PARAM_ERROR, "请求体格式错误");
    }

    /**
     * 兜底异常：记录完整堆栈，对外只返回通用提示，避免泄露内部细节。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(BusinessException.SERVER_ERROR, "系统繁忙，请稍后重试");
    }
}
