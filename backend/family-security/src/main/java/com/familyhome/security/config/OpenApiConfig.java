package com.familyhome.security.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * 统一 OpenAPI 文档配置：所有微服务暴露 {@code /v3/api-docs} 与 {@code /swagger-ui}，
 * 并声明 Bearer JWT 安全方案。文档不包含任何密钥或内部配置信息。
 */
@Configuration
@OpenAPIDefinition(
        info =
                @Info(
                        title = "家庭综合平台 API",
                        version = "0.1.0",
                        description =
                                "Spring Cloud Alibaba 微服务。所有受保护接口需携带 Authorization: Bearer <JWT>。"))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfig {}
