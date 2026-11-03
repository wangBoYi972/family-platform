package com.familyhome.common.exception;

import com.familyhome.common.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 *
 * <p>统一处理各类异常,返回规范的 API 响应格式。
 * 符合《阿里巴巴Java开发手册》异常处理规范。
 *
 * @author Family Platform Team
 * @since 1.0.0
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理参数校验异常(@Valid)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Map<String, String>> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.warn("参数校验失败: {}", errors);
        return ApiResponse.failure(400, "参数校验失败: " + errors);
    }

    /**
     * 处理约束违反异常
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("约束违反: {}", ex.getMessage());
        return ApiResponse.failure(400, "参数不合法: " + ex.getMessage());
    }

    /**
     * 处理类型转换异常
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = String.format("参数 '%s' 类型错误,需要类型: %s",
                ex.getName(),
                ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "未知");
        log.warn("参数类型转换失败: {}", message);
        return ApiResponse.failure(400, message);
    }

    /**
     * 处理 ResponseStatusException(Spring 标准异常)
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ApiResponse<Void> handleResponseStatus(ResponseStatusException ex) {
        int statusCode = ex.getStatusCode().value();
        String reason = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();

        if (statusCode >= 500) {
            log.error("服务器错误: {}", reason, ex);
        } else {
            log.warn("客户端错误: {}", reason);
        }

        return ApiResponse.failure(statusCode, reason);
    }

    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("非法参数: {}", ex.getMessage());
        return ApiResponse.failure(400, ex.getMessage());
    }

    /**
     * 处理非法状态异常
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleIllegalState(IllegalStateException ex) {
        log.error("非法状态: {}", ex.getMessage(), ex);
        return ApiResponse.failure(500, "操作失败: " + ex.getMessage());
    }

    /**
     * 处理未知异常(兜底)
     *
     * <p>注意:根据《阿里巴巴Java开发手册》,这里不应该捕获 Exception,
     * 但作为全局异常处理器的兜底逻辑,这是必要的。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGenericException(Exception ex) {
        log.error("未处理的异常: {}", ex.getMessage(), ex);
        return ApiResponse.failure(500, "服务器内部错误,请联系管理员");
    }
}
