package com.familyhome.user.security;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 跨实例登录/注册尝试限流。
 *
 * <p>基于 Redis 原子递增实现固定窗口计数，多实例部署下共享同一计数，避免单机 HashMap
 * 被攻击者轮询不同节点绕过。Bean 缺失或 Redis 连不上时降级放行，不阻断正常登录。
 */
@Component
public class LoginRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final Duration WINDOW = Duration.ofMinutes(10);

    @Autowired(required = false)
    private StringRedisTemplate redis;

    /**
     * 按手机号限流：窗口内超过 maxAttempts 次则抛 429。
     *
     * @param namespace 限流键命名空间，区分登录/注册
     * @param phone      手机号
     * @param maxAttempts 窗口内最大尝试次数
     */
    public void checkAndIncrement(String namespace, String phone, int maxAttempts) {
        if (redis == null) {
            return;
        }

        try {
            String key = "family:loginlimit:" + namespace + ":" + phone;
            Long count = redis.opsForValue().increment(key);
            if (count == null) return;
            if (count == 1L) redis.expire(key, WINDOW);
            if (count > maxAttempts) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "尝试次数过多，请十分钟后再试");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RedisConnectionFailureException e) {
            log.warn("登录限流 Redis 不可用，降级放行: {}", e.getMessage());
        } catch (RuntimeException e) {
            // Lettuce 等底层连接异常也可能包在 RuntimeException 里
            if (e.getCause() instanceof RedisConnectionFailureException
                    || e.getClass().getName().contains("Redis")) {
                log.warn("登录限流 Redis 异常，降级放行: {}", e.getMessage());
                return;
            }
            throw e;
        }
    }
}
