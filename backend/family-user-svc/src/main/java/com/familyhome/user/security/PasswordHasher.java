package com.familyhome.user.security;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String dummyHash = encoder.encode(UUID.randomUUID().toString());

    public String encode(String password) {
        if (password == null
                || password.isBlank()
                || password.length() < 12
                || password.length() > 64
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码需要 12 至 64 个字符，UTF-8 编码不得超过 72 字节");
        }
        return encoder.encode(password);
    }

    public boolean matches(String password, String hash) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) return false;
        // 未登记账号也执行密码散列比较，减少账号枚举的时间差。
        boolean matched = encoder.matches(password, hash == null ? dummyHash : hash);
        return hash != null && matched;
    }
}
