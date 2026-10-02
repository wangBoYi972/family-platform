package com.familyhome.gateway.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "family.auth")
@Data
public class GatewayAuthProperties {
    private String jwtSecret = "";
}
