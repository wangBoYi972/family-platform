package com.familyhome.gateway;

import com.familyhome.gateway.auth.GatewayAuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GatewayAuthProperties.class)
public class FamilyGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(FamilyGatewayApplication.class, args);
    }
}
