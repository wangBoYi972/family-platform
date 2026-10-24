package com.familyhome.user.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 邀请码的生成与摘要。
 *
 * <p>明文形如 {@code FAM-7K2M-QX4P}，使用去掉 0/O/1/I 的字符集，避免口头转述时混淆。
 * 明文只在发放接口返回一次，落库的是 SHA-256 摘要，因此数据库泄露也无法反推可用邀请码。
 */
@Component
public class InviteCodeGenerator {
    /** 去掉易混淆字符 0、O、1、I 后的 32 字符表，8 位明文约 1.1e12 种组合。 */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "FAM-";

    public String generate() {
        var body = new StringBuilder(8);
        for (int i = 0; i < 8; i++) body.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        return PREFIX + body.substring(0, 4) + "-" + body.substring(4);
    }

    /** 归一化用户输入：去空白、转大写，容忍用户少输连字符。 */
    public String normalize(String raw) {
        if (raw == null) return "";
        return raw.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    public String hash(String normalized) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** 列表展示用尾号，不泄露可注册能力。 */
    public String hint(String normalized) {
        if (normalized.length() <= 4) return "****";
        return normalized.substring(normalized.length() - 4);
    }
}
