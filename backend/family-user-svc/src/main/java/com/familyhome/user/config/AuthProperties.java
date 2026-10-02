package com.familyhome.user.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "family.auth")
@Validated
@Data
public class AuthProperties {
    private boolean mockLoginEnabled;
    private String mockCode = "";
    private String jwtSecret = "";

    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(1440)
    private long tokenExpirationMinutes = 60;
}
