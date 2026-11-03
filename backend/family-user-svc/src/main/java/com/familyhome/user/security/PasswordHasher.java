package com.familyhome.user.security;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {
    private static final Logger log = LoggerFactory.getLogger(PasswordHasher.class);
    private BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private String dummyHash = encoder.encode(UUID.randomUUID().toString());

    public String encode(String password) {
        if (password == null
                || password.isBlank()
                || password.length() < 6
                || password.length() > 64
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码需要 6 至 64 个字符，UTF-8 编码不得超过 72 字节");
        }
        return encoder.encode(password);
    }

    public boolean matches(String password, String hash) {
        log.info("PasswordHasher.matches 被调用: password={}, hash={}", password, hash != null ? hash.substring(0, 20) : "null");
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            log.info("密码为空或超长,返回 false");
            return false;
        }
        // 未登记账号也执行密码散列比较，减少账号枚举的时间差。
        boolean matched = encoder.matches(password, hash == null ? dummyHash : hash);
        log.info("BCrypt 匹配结果: matched={}, hash != null: {}", matched, hash != null);
        boolean result = hash != null && matched;
        log.info("最终返回: {}", result);
        return result;
    }
}
