package com.familyhome.gateway.filter;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * 跨域放行。
 *
 * <p>生产环境由 Nginx 把 H5 与网关暴露在同一域名下，不需要跨域；本地开发时 H5 跑在 5173、
 * 网关跑在 8080，属于不同源，必须显式放行，否则前端所有请求都会被浏览器拦下。
 * 因此默认关闭，仅 local profile 打开，且只允许白名单来源，避免生产误开造成凭据泄露。
 */
@Configuration
@ConditionalOnProperty(name = "family.gateway.cors.enabled", havingValue = "true")
public class GatewayCorsConfiguration {

    @Bean
    public CorsWebFilter corsWebFilter(
            @Value("${family.gateway.cors.allowed-origins:}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // 前端需要读到的响应头：请求链路与限流提示
        config.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsWebFilter(source);
    }
}
