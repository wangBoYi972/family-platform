package com.familyhome.messaging;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
@ConditionalOnProperty(name = "family.messaging.enabled", havingValue = "true")
public class RedisIdempotencyLock {
    @Autowired private StringRedisTemplate redis;
    public Lease tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent("family:lock:" + key, token, ttl);
        return Boolean.TRUE.equals(acquired) ? new Lease("family:lock:" + key, token) : null;
    }
    public record Lease(String key, String token) {}
    public void release(Lease lease) {
        if (lease != null && lease.token().equals(redis.opsForValue().get(lease.key()))) redis.delete(lease.key());
    }
}
