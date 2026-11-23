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
        // 与 PasswordSetDTO / RegisterDTO / 前端保持同一条线：12 位下限。
        // 之前这里是 6 位，接口层校验一旦漏掉（例如内部调用）就能落下弱口令。
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
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        // 未登记账号也执行密码散列比较，减少账号枚举的时间差。
        boolean matched = encoder.matches(password, hash == null ? dummyHash : hash);
        return hash != null && matched;
    }
}
