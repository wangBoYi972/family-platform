package com.familyhome.user.security;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {
    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    @DisplayName("合法口令可编码并校验通过")
    void encodeAndMatch() {
        String hash = hasher.encode("abc123def456");
        assertThat(hash).startsWith("$2");
        assertThat(hasher.matches("abc123def456", hash)).isTrue();
        assertThat(hasher.matches("abc123def457", hash)).isFalse();
    }

    @Test
    @DisplayName("非法口令在编码层被拒绝（长度与字节边界）")
    void rejectsInvalid() {
        assertThatThrownBy(() -> hasher.encode("short1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.encode("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.encode(null)).isInstanceOf(IllegalArgumentException.class);
        // 64 字符内但 UTF-8 超 72 字节（全中文）
        String chinese = "家".repeat(30);
        assertThat(chinese.length()).isEqualTo(30);
        assertThatThrownBy(() -> hasher.encode(chinese)).isInstanceOf(IllegalArgumentException.class);
        assertThat(hasher.matches(chinese, hasher.encode("abc123def456"))).isFalse();
    }

    @Test
    @DisplayName("未登记账号（null 散列）不抛异常，返回不匹配")
    void unmatchedHashReturnsFalse() {
        assertThat(hasher.matches("abc123def456", null)).isFalse();
    }
}
