package com.familyhome.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyhome.security.SessionPrincipal;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
@ConditionalOnProperty(name = "family.messaging.enabled", havingValue = "true")
public class RedisTicketStore {
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper mapper;

    public String issue(SessionPrincipal principal) {
        try {
            String ticket = UUID.randomUUID().toString();
            redis.opsForValue().set("family:ws:ticket:" + ticket, mapper.writeValueAsString(principal), Duration.ofSeconds(30));
            return ticket;
        } catch (Exception ex) {
            throw new IllegalStateException("无法创建 WebSocket ticket", ex);
        }
    }

    public SessionPrincipal consume(String ticket) {
        if (ticket == null || ticket.isBlank()) return null;
        String key = "family:ws:ticket:" + ticket;
        String raw = redis.opsForValue().getAndDelete(key);
        if (raw == null) return null;
        try { return mapper.readValue(raw, SessionPrincipal.class); }
        catch (Exception ex) { throw new IllegalStateException("WebSocket ticket 无效", ex); }
    }
}
