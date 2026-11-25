package com.familyhome.gateway.auth;

import org.springframework.beans.factory.annotation.Autowired;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {
    @Autowired
    private GatewayAuthProperties properties;

    public Mono<Void> filter(
            ServerWebExchange exchange,
            org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        // WebSocket 握手无法携带 Authorization 头，改用一次性 ticket 鉴权，由媒体/IM 服务校验。
        if (path.startsWith("/ws/")) return chain.filter(exchange);
        // 家侧网关：注册靠配对码；上报靠 X-Gateway-Token，由 iot-svc 校验。
        if (path.startsWith("/api/iot/gateway/")) return chain.filter(exchange);
        if ((exchange.getRequest().getMethod() == HttpMethod.POST
                        && (path.equals("/api/auth/login")
                                || path.equals("/api/auth/password-login")
                                || path.equals("/api/auth/register")))
                || path.equals("/actuator/health")) return chain.filter(exchange);
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) return unauthorized(exchange);
        try {
            Jwts.parser()
                    .verifyWith(signingKey())
                    .requireIssuer("family-home")
                    .build()
                    .parseSignedClaims(auth.substring(7));
            return chain.filter(exchange);
        } catch (JwtException | IllegalArgumentException ex) {
            return unauthorized(exchange);
        }
    }

    private SecretKey signingKey() {
        byte[] key = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) throw new IllegalArgumentException("JWT 密钥未配置或长度不足");
        return Keys.hmacShaKeyFor(key);
    }

    private Mono<Void> unauthorized(ServerWebExchange e) {
        e.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return e.getResponse().setComplete();
    }

    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
