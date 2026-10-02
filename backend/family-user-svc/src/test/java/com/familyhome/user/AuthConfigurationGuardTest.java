package com.familyhome.user;

import static org.assertj.core.api.Assertions.*;

import com.familyhome.user.config.AuthConfigurationGuard;
import com.familyhome.user.config.AuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

class AuthConfigurationGuardTest {
    @Test
    @DisplayName("正式环境误开验证码或者混用 local 必须启动失败")
    void rejectsUnsafeProfiles() {
        var properties = new AuthProperties();
        properties.setMockLoginEnabled(true);
        var environment = new MockEnvironment().withProperty("x", "x");
        var guard = new AuthConfigurationGuard();
        ReflectionTestUtils.setField(guard, "properties", properties);
        ReflectionTestUtils.setField(guard, "environment", environment);
        assertThatThrownBy(
                        () -> ReflectionTestUtils.invokeMethod(guard, "validate"))
                .isInstanceOf(IllegalStateException.class);
        environment.setActiveProfiles("local", "prod");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(guard, "validate"))
                .isInstanceOf(IllegalStateException.class);
        environment.setActiveProfiles("local");
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(guard, "validate"))
                .doesNotThrowAnyException();
    }
}
