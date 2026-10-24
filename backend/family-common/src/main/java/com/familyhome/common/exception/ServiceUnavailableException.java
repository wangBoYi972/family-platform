package com.familyhome.common.exception;

/**
 * 服务不可用异常
 * 当依赖的微服务调用失败时抛出
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
