package com.familyhome.user.security;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 跨实例登录/注册尝试限流。
 *
 * <p>基于 Redis 原子递增实现固定窗口计数。Redis 未配置或连接失败时：
 * 默认 fail-closed（503），避免限流失效时无限试邀请码；本地/CI 可通过
 * {@code family.auth.login-rate-limit-failopen=true} 降级放行。
 */
@Component
public class LoginRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final Duration WINDOW = Duration.ofMinutes(10);

    @Autowired(required = false)
    private StringRedisTemplate redis;

    /**
     * Redis 不可用时是否放行。生产默认 false；测试/无 Redis 联调设 true。
     * 也可用环境变量 FAMILY_LOGIN_RATE_LIMIT_FAILOPEN 覆盖。
     */
    @Value("${family.auth.login-rate-limit-failopen:false}")
    private boolean failOpen;

    public void checkAndIncrement(String namespace, String phone, int maxAttempts) {
        if (redis == null) {
            onRedisUnavailable("未配置 Redis");
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
        } catch (Exception e) {
            onRedisUnavailable(e.getMessage());
        }
    }

    private void onRedisUnavailable(String reason) {
        boolean open = failOpen || Boolean.parseBoolean(System.getenv("FAMILY_LOGIN_RATE_LIMIT_FAILOPEN"));
        if (open) {
            log.warn("登录限流 Redis 不可用，已按 fail-open 放行：{}", reason);
            return;
        }
        log.error("登录限流 Redis 不可用，fail-closed：{}", reason);
        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "登录服务暂时不可用，请稍后再试");
    }
}
