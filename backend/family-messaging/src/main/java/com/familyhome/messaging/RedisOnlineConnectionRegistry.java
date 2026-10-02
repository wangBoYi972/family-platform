package com.familyhome.messaging;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
@ConditionalOnProperty(name = "family.messaging.enabled", havingValue = "true")
public class RedisOnlineConnectionRegistry {
    @Autowired private StringRedisTemplate redis;
    private final String instanceId = UUID.randomUUID().toString();
    public String register(long householdId, String sessionId) {
        String member = instanceId + ":" + sessionId;
        redis.opsForSet().add("family:online:" + householdId, member);
        redis.expire("family:online:" + householdId, Duration.ofMinutes(2));
        return member;
    }
    public void touch(long householdId, String member) { redis.opsForSet().add("family:online:" + householdId, member); redis.expire("family:online:" + householdId, Duration.ofMinutes(2)); }
    public void unregister(long householdId, String member) { redis.opsForSet().remove("family:online:" + householdId, member); }
    public void broadcast(long householdId) { redis.convertAndSend("family:ws:broadcast", String.valueOf(householdId)); }
}
