package com.familyhome.im.messaging;

import com.familyhome.im.websocket.ImSocket;

import java.time.Duration;
import java.util.Map;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 消费端用 Redis 记录事件编号，保证 Rabbit 重投不会重复广播。
 */
@Component
@ConditionalOnProperty(name = "family.messaging.enabled", havingValue = "true")
public class FamilyEventConsumer {
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ImSocket socket;

    @RabbitListener(queues = "${family.messaging.queue:family-im-svc.events}")
    public void consume(Map<String, Object> event) {
        String eventId = String.valueOf(event.get("eventId"));
        Boolean first = redis.opsForValue().setIfAbsent("family:consumer:im:" + eventId, "1", Duration.ofDays(7));
        if (!Boolean.TRUE.equals(first)) return;
        Object household = event.get("householdId");
        if (household instanceof Number n) socket.notifyLocalEvent(n.longValue());
    }
}
