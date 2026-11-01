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
    /** 聊天消息由发送方在事务提交后直接推送，这里不再重复广播，见 ImServiceImpl#pushAfterCommit。 */
    private static final String MESSAGE_EVENT = "message.sent";

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
        if (!(household instanceof Number n)) return;
        // 新消息走的是带内容的帧，客户端收到就能上屏；这里再发一次 refresh 只会让所有人多拉一遍接口。
        if (MESSAGE_EVENT.equals(String.valueOf(event.get("eventType")))) return;
        socket.notifyLocalEvent(n.longValue());
    }
}
