package com.familyhome.archive.security;

import static org.assertj.core.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.*;

class ArchiveCipherTest {
    private static ArchiveCipher cipher;

    @BeforeAll
    static void setUp() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        cipher = new ArchiveCipher(Base64.getEncoder().encodeToString(key));
    }

    @Test
    @DisplayName("加密往返一致，密文不包含明文")
    void roundTrip() {
        String body = "家庭成员健康档案正文，可能包含敏感信息";
        String sealed = cipher.encrypt(body, "10:arch-1");
        assertThat(sealed).isNotEqualTo(body).doesNotContain("健康档案");
        assertThat(cipher.decrypt(sealed, "10:arch-1")).isEqualTo(body);
    }

    @Test
    @DisplayName("每次加密随机 IV，同一输入两次结果不同")
    void randomIv() {
        String a = cipher.encrypt("same", "10:arch-1");
        String b = cipher.encrypt("same", "10:arch-1");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("密文被篡改后解密失败")
    void tamperDetected() {
        String sealed = cipher.encrypt("important", "10:arch-1");
        String tampered = sealed.substring(0, sealed.length() - 2) + "AA";
        assertThatThrownBy(() -> cipher.decrypt(tampered, "10:arch-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("错误上下文（跨家庭/跨档案）解密失败，防止密文被搬移")
    void wrongContextRejected() {
        String sealed = cipher.encrypt("secret", "10:arch-1");
        assertThatThrownBy(() -> cipher.decrypt(sealed, "20:arch-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(sealed, "10:arch-2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("密钥长度非 32 字节直接拒绝启动")
    void rejectsWrongKeyLength() {
        byte[] shortKey = new byte[16];
        assertThatThrownBy(() -> new ArchiveCipher(Base64.getEncoder().encodeToString(shortKey)))
                .isInstanceOf(IllegalStateException.class);
    }
}
