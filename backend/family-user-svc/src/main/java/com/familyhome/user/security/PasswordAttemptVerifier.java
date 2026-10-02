package com.familyhome.user.security;

import org.springframework.beans.factory.annotation.Autowired;
import com.familyhome.user.mapper.UserCredentialMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 调用方必须持有白名单行锁，并对 InvalidCredentialsException 保留失败计数。 */
@Component
public class PasswordAttemptVerifier {
    @Autowired
    private UserCredentialMapper credentials;
    @Autowired
    private PasswordHasher hasher;

    // 认证失败需要提交失败计数；不能让内层代理把外层事务标成 rollback-only。
    @Transactional(
            propagation = Propagation.MANDATORY,
            noRollbackFor = InvalidCredentialsException.class)
    public void verify(String phone, String password, boolean changingPassword) {
        var credential = credentials.selectById(phone);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (credential != null
                && credential.getLockedUntil() != null
                && credential.getLockedUntil().isAfter(now))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "尝试次数过多，请十五分钟后再试");
        boolean matched =
                hasher.matches(password, credential == null ? null : credential.getPasswordHash());
        if (!matched) {
            if (credential != null) {
                int failed = credential.getLockedUntil() == null ? credential.getFailedAttempts() + 1 : 1;
                credentials.recordAttempt(phone, failed, failed >= 5 ? now.plusMinutes(15) : null);
            }
            if (changingPassword) throw new InvalidCredentialsException(HttpStatus.FORBIDDEN, "当前密码不正确");
            throw new InvalidCredentialsException();
        }
        credentials.recordAttempt(phone, 0, null);
    }
}
