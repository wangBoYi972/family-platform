package com.familyhome.gateway.ratelimit;

import org.springframework.beans.factory.annotation.Autowired;
import com.familyhome.gateway.auth.GatewayAuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway 限流：Redis 令牌桶。
 *
 * <p>已登录请求按用户 id 限流（JWT 已由 {@code JwtAuthenticationFilter} 前置校验，此处仅读 subject），
 * 未登录请求按客户端 IP 限流。令牌桶以原子 Lua 脚本实现（容量/速率可配），
 * 仅当 {@code family.gateway.rate-limit.enabled=true} 时启用，本地开发无需 Redis 不受影响。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "family.gateway.rate-limit.enabled", havingValue = "true")
public class GatewayRateLimitConfiguration {
    private static final Logger log = LoggerFactory.getLogger(GatewayRateLimitConfiguration.class);

    /** 令牌桶 Lua：按当前时间回填令牌，桶满丢弃溢出；每次取 1 个令牌。返回 1=放行，0=拒绝。 */
    private static final String TOKEN_BUCKET_LUA =
            """
            local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(bucket[1])
            local ts = tonumber(bucket[2])
            if tokens == nil then tokens = tonumber(ARGV[1]) end
            if ts == nil then ts = tonumber(ARGV[3]) end
            local elapsed = (tonumber(ARGV[3]) - ts) / 1000
            tokens = math.min(tonumber(ARGV[1]), tokens + elapsed * tonumber(ARGV[2]))
            if tokens >= 1 then
              redis.call('HSET', KEYS[1], 'tokens', tokens - 1, 'ts', ARGV[3])
              redis.call('PEXPIRE', KEYS[1], 3600000)
              return 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], 3600000)
            return 0
            """;

    @Autowired
    private GatewayAuthProperties authProperties;

    @Bean
    public TokenBucketRateLimiter tokenBucketRateLimiter(
            ReactiveStringRedisTemplate redisTemplate,
            @Value("${family.gateway.rate-limit.replenish-rate:20}") int replenishRate,
            @Value("${family.gateway.rate-limit.burst-capacity:40}") int burstCapacity) {
        if (replenishRate <= 0 || burstCapacity < replenishRate)
            throw new IllegalStateException(
                    "限流配置非法：replenish-rate 必须为正，burst-capacity 不得小于 replenish-rate");
        return new TokenBucketRateLimiter(redisTemplate, replenishRate, burstCapacity);
    }

    @Bean
    public KeyResolver rateLimitKeyResolver() {
        return exchange -> {
            String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (auth != null && auth.startsWith("Bearer ")) {
                try {
                    Claims claims =
                            Jwts.parser()
                                    .verifyWith(
                                            Keys.hmacShaKeyFor(
                                                    authProperties.getJwtSecret()
                                                            .getBytes(StandardCharsets.UTF_8)))
                                    .requireIssuer("family-home")
                                    .build()
                                    .parseSignedClaims(auth.substring(7))
                                    .getPayload();
                    return Mono.just("user:" + claims.getSubject());
                } catch (RuntimeException ignored) {
                    // 令牌解析失败交由鉴权过滤器返回 401，这里退回 IP 键。
                }
            }
            String ip =
                    exchange.getRequest().getRemoteAddress() != null
                            ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                            : "unknown";
            return Mono.just("ip:" + ip);
        };
    }

    @Bean
    public GlobalFilter rateLimitGlobalFilter(
            TokenBucketRateLimiter tokenBucketRateLimiter, KeyResolver rateLimitKeyResolver) {
        return new RateLimitGlobalFilter(tokenBucketRateLimiter, rateLimitKeyResolver);
    }

    /** 原子 Lua 令牌桶。 */
    static final class TokenBucketRateLimiter {
        private final ReactiveStringRedisTemplate redisTemplate;
        private final int replenishRate;
        private final int burstCapacity;

        TokenBucketRateLimiter(ReactiveStringRedisTemplate redisTemplate, int replenishRate, int burstCapacity) {
            this.redisTemplate = redisTemplate;
            this.replenishRate = replenishRate;
            this.burstCapacity = burstCapacity;
        }

        Mono<Boolean> isAllowed(String route, String key) {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(TOKEN_BUCKET_LUA, Long.class);
            return redisTemplate
                    .execute(
                            script,
                            List.of("family:rl:" + route + ":" + key),
                            List.of(
                                    String.valueOf(burstCapacity),
                                    String.valueOf(replenishRate),
                                    String.valueOf(System.currentTimeMillis())))
                    .next()
                    .map(result -> result != null && result == 1L);
        }
    }

    private static final class RateLimitGlobalFilter implements GlobalFilter, Ordered {
        private final TokenBucketRateLimiter limiter;
        private final KeyResolver keyResolver;

        private RateLimitGlobalFilter(TokenBucketRateLimiter limiter, KeyResolver keyResolver) {
            this.limiter = limiter;
            this.keyResolver = keyResolver;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            String path = exchange.getRequest().getURI().getPath();
            if (path.startsWith("/actuator")) return chain.filter(exchange);
            return keyResolver
                    .resolve(exchange)
                    .flatMap(
                            key ->
                                    limiter.isAllowed("family-gateway", key)
                                            .flatMap(
                                                    allowed -> {
                                                        if (!allowed) {
                                                            log.warn(
                                                                    "rate limited key={} path={}",
                                                                    key,
                                                                    path);
                                                            exchange.getResponse()
                                                                    .setStatusCode(
                                                                            HttpStatus.TOO_MANY_REQUESTS);
                                                            exchange.getResponse()
                                                                    .getHeaders()
                                                                    .add(
                                                                            HttpHeaders.RETRY_AFTER,
                                                                            "1");
                                                            return exchange.getResponse()
                                                                    .setComplete();
                                                        }
                                                        return chain.filter(exchange);
                                                    }));
        }

        @Override
        public int getOrder() {
            // 位于 JWT 鉴权过滤器之后，使用已验证身份作为限流键。
            return Ordered.HIGHEST_PRECEDENCE + 20;
        }
    }
}
