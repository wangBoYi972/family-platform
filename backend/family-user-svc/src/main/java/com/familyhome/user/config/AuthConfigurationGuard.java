package com.familyhome.user.config;

import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 在启动时拒绝将测试认证带入正式环境。 */
@Component
public class AuthConfigurationGuard {
    @Autowired
    private AuthProperties properties;
    @Autowired private Environment environment;

    @PostConstruct
    void validate() {
        var profiles = Arrays.asList(environment.getActiveProfiles());
        if (profiles.contains("local") && profiles.contains("prod"))
            throw new IllegalStateException("local 与 prod 不得同时启用");
        if (properties.isMockLoginEnabled() && !profiles.contains("local"))
            throw new IllegalStateException("测试验证码只能在 local 环境启用");
    }
}
