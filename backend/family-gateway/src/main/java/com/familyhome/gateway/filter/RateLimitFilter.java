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
 * 按 IP 的简单固定窗口限流（每秒 10 次）。
 *
 * <p>这是早期的兜底实现，和 {@code GatewayRateLimitConfiguration} 的 Redis 令牌桶是两个独立开关。
 * 默认关闭：它不区分接口、也没有用户维度，开启后会和令牌桶重复限流；而且 WebSocket 升级请求
 * 一旦被 429 掉，客户端只会看到「握手失败」，表现为整通电话打不通。需要时用
 * {@code family.gateway.rate-limit.simple.enabled=true} 单独开启。
 */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "family.gateway.rate-limit.simple.enabled",
        havingValue = "true")
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

        // WebSocket 升级只发生一次，被限流后客户端不会重试，直接断掉整通通话，必须放行
        String upgrade = exchange.getRequest().getHeaders().getUpgrade();
        if (upgrade != null && "websocket".equalsIgnoreCase(upgrade)) {
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

        } catch (RuntimeException e) {
            log.error("限流检查失败", e);
            // Redis 故障时的降级策略：返回 503 而不是放行，防止限流失效被当作 DDoS 入口
            // 如果允许故障继续服务，应该配置 family.gateway.rate-limit.failopen=true
            if (!Boolean.parseBoolean(System.getenv("FAMILY_GATEWAY_RATE_LIMIT_FAILOPEN"))) {
                exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                return exchange.getResponse().setComplete();
            }
        }

        return chain.filter(exchange);
    }

    private String getClientIp(ServerWebExchange exchange) {
        // 优先取 X-Real-IP（由受信任的反向代理设置，不可被客户端注入）
        String realIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        // X-Forwarded-For 取最后一个 IP：链路末端最靠近真实网络入口，客户端伪造的值在最左侧，
        // 攻击者无法控制代理在链路末尾追加的地址。
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            return parts[parts.length - 1].trim();
        }
        return Objects.requireNonNull(exchange.getRequest().getRemoteAddress())
                .getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        return -100; // 较高优先级,在认证之前执行
    }
}
