package com.familyhome.security;

import com.familyhome.common.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(feign.FeignException.class)
    ResponseEntity<ApiResponse<Void>> remote(feign.FeignException ex) {
        int status =
                ex.status() == 404 ? 404 : ex.status() == 401 ? 401 : ex.status() == 403 ? 403 : 503;
        return ResponseEntity.status(status)
                .body(ApiResponse.failure(status, status == 503 ? "关联服务暂时不可用，请稍后重试" : "关联资源不存在或未获授权"));
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    ResponseEntity<ApiResponse<Void>> duplicate(Exception ex) {
        return ResponseEntity.status(409).body(ApiResponse.failure(409, "记录已存在，请刷新后重试"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiResponse<Void>> status(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(ApiResponse.failure(ex.getStatusCode().value(), ex.getReason()));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<ApiResponse<Void>> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(400, "输入格式不正确，请检查必填字段与长度"));
    }
}
