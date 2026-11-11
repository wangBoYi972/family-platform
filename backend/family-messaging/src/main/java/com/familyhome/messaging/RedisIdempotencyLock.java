package com.familyhome.messaging;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
@ConditionalOnProperty(name = "family.messaging.enabled", havingValue = "true")
public class RedisIdempotencyLock {
    @Autowired private RedisTemplate<String, String> redis;

    public Lease tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent("family:lock:" + key, token, ttl);
        return Boolean.TRUE.equals(acquired) ? new Lease("family:lock:" + key, token) : null;
    }

    public record Lease(String key, String token) {}

    public void release(Lease lease) {
        if (lease == null) return;

        // 使用 Lua 脚本保证原子性：仅在 token 匹配时删除
        String luaScript =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";

        RedisScript<Long> script = RedisScript.of(luaScript, Long.class);
        redis.execute(script, Collections.singletonList(lease.key()), lease.token());
    }
}
