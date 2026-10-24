package com.familyhome.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * 全局限流过滤器
 * 基于 IP 的简单限流策略
 */
@Slf4j
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    // 默认限流: 每秒 10 次请求
    private static final int DEFAULT_LIMIT = 10;
    private static final Duration WINDOW = Duration.ofSeconds(1);

    @Autowired(required = false)
    private StringRedisTemplate redis;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 如果 Redis 不可用,跳过限流
        if (redis == null) {
            return chain.filter(exchange);
        }

        String ip = getClientIp(exchange);
        String key = "rate_limit:ip:" + ip;

        try {
            // 原子递增并获取当前计数
            Long count = redis.opsForValue().increment(key);

            if (count == null) {
                count = 0L;
            }

            // 首次请求设置过期时间
            if (count == 1) {
                redis.expire(key, WINDOW);
            }

            // 超过限制
            if (count > DEFAULT_LIMIT) {
                log.warn("IP {} 触发限流,当前计数: {}", ip, count);
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                return exchange.getResponse().setComplete();
            }

            // 添加限流信息到响应头
            exchange.getResponse().getHeaders().add("X-RateLimit-Limit", String.valueOf(DEFAULT_LIMIT));
            exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", String.valueOf(DEFAULT_LIMIT - count));

        } catch (Exception e) {
            log.error("限流检查失败,放行请求", e);
        }

        return chain.filter(exchange);
    }

    private String getClientIp(ServerWebExchange exchange) {
        String ip = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (ip != null && !ip.isEmpty()) {
            return ip.split(",")[0].trim();
        }

        ip = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (ip != null && !ip.isEmpty()) {
            return ip;
        }

        return Objects.requireNonNull(exchange.getRequest().getRemoteAddress())
                .getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        return -100; // 较高优先级,在认证之前执行
    }
}
