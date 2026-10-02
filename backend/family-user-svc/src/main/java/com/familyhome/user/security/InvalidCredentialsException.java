package com.familyhome.user.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 密码拒绝不会回滚已记录的失败次数；其他业务异常仍正常回滚。 */
public class InvalidCredentialsException extends ResponseStatusException {
    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "手机号或密码无效");
    }

    public InvalidCredentialsException(HttpStatus status, String message) {
        super(status, message);
    }
}
