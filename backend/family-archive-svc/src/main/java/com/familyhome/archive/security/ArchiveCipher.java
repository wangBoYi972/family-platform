package com.familyhome.archive.security;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ArchiveCipher {
    // 优先读取 family.archive.key（各 profile 的 yml 配置），未配置时回退到 FAMILY_ARCHIVE_KEY 环境变量。
    @Value("${family.archive.key:${FAMILY_ARCHIVE_KEY:}}") private String encoded;
    private SecretKeySpec key;

    public ArchiveCipher() {}

    public ArchiveCipher(String encoded) {
        this.encoded = encoded;
        initialize();
    }

    @PostConstruct
    void initialize() {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length != 32)
            throw new IllegalStateException("FAMILY_ARCHIVE_KEY 必须为 32 字节随机密钥的 Base64 编码");
        key = new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(String value, String context) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv)
                    + ":"
                    + Base64.getEncoder()
                            .encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("档案加密失败", ex);
        }
    }

    public String decrypt(String value, String context) {
        try {
            String[] parts = value.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(
                    cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("档案解密失败", ex);
        }
    }
}
