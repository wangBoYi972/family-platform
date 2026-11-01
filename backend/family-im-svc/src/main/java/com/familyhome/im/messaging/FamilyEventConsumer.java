package com.familyhome.im.messaging;

import com.familyhome.im.websocket.ImSocket;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

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
    /** 消息类事件都由发起方在事务提交后直接推送（含撤回帧），这里不再重复广播。 */
    private static final Set<String> PUSHED_EVENTS = Set.of("message.sent", "message.deleted");

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
        // 新消息/撤回走的是带内容的帧，客户端收到即可处理；这里再发一次 refresh 只会让所有人多拉一遍接口。
        String type = String.valueOf(event.get("eventType"));
        if (PUSHED_EVENTS.contains(type)) return;
        // 刷新帧带上事件类型：客户端据此只重拉受影响的面板（任务变了不必连相册一起刷）。
        // 走结构化帧广播（而非只发本机）还修了一个多实例问题：Rabbit 队列是竞争消费，
        // 事件只会被其中一台实例收到，refresh 只发本机的话其它实例上的连接永远收不到。
        socket.notifyFamilyEvent(n.longValue(), Map.of("type", "refresh", "event", type));
    }
}
