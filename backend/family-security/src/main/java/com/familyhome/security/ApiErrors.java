package com.familyhome.security;

import com.familyhome.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(feign.FeignException.class)
    ResponseEntity<ApiResponse<Void>> remote(feign.FeignException ex) {
        int upstream = ex.status();
        if (upstream == 404)
            return ResponseEntity.status(404)
                    .body(ApiResponse.failure(404, "关联资源不存在或无权访问"));
        if (upstream == 401)
            return ResponseEntity.status(401).body(ApiResponse.failure(401, "登录已失效，请重新登录"));
        if (upstream == 403)
            return ResponseEntity.status(403).body(ApiResponse.failure(403, "无权访问关联资源"));
        // 上游明确拒绝了这次请求（参数/冲突），不能报成「服务不可用」，否则前端会误导用户重试。
        if (upstream >= 400 && upstream < 500)
            return ResponseEntity.status(400).body(ApiResponse.failure(400, "关联服务拒绝了这次请求，请检查填写内容"));
        return ResponseEntity.status(503)
                .body(ApiResponse.failure(503, "关联服务暂时不可用，请稍后重试"));
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

    /**
     * 兜底：任何没被上面接住的异常都返回同一套 {code,msg,data}。
     *
     * <p>少了这个兜底时，未预期异常会落到 Spring 默认的 /error，响应体变成
     * {timestamp,status,error,path}，前端按 code 判断的地方直接失效。
     *
     * <p>注意不能把 Spring 自己的 4xx 一起吞成 500：路径不存在（404）、方法不支持（405）
     * 都实现 {@link org.springframework.web.ErrorResponse}，要保留原状态码。
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unknown(Exception ex) {
        if (ex instanceof org.springframework.web.ErrorResponse known) {
            int status = known.getStatusCode().value();
            if (status < 500) {
                String msg =
                        switch (status) {
                            case 404 -> "接口不存在";
                            case 405 -> "请求方法不支持";
                            default -> "请求无法处理";
                        };
                return ResponseEntity.status(status).body(ApiResponse.failure(status, msg));
            }
        }
        log.error("未处理的异常", ex);
        return ResponseEntity.status(500).body(ApiResponse.failure(500, "服务内部错误，请稍后重试"));
    }
}
