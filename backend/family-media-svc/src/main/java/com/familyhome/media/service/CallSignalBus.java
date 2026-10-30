package com.familyhome.media.service;

import com.familyhome.media.websocket.CallSocket;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * 通话信令总线：先投递本机连接，再通过 Redis 广播到其它实例。
 *
 * <p>水平扩容后同一通话的两个人可能落在不同实例上，因此信令必须跨实例可达。
 * 本地开发未启用 Redis 时自动降级为单机投递，不影响功能验证。
 */
@Component
public class CallSignalBus {
    private static final Logger log = LoggerFactory.getLogger(CallSignalBus.class);
    public static final String CHANNEL = "family:call:signal";

    private record Envelope(String origin, CallSignal signal) {}

    private final String instanceId = UUID.randomUUID().toString();

    @Autowired private ObjectProvider<StringRedisTemplate> redisProvider;
    @Autowired private ObjectProvider<CallSocket> socketProvider;
    @Autowired private ObjectMapper mapper;

    /** 与 RedisTicketStore 保持同一开关：未开启时信令只在单实例内投递。 */
    @org.springframework.beans.factory.annotation.Value("${family.messaging.enabled:false}")
    private boolean messagingEnabled;

    private StringRedisTemplate redis;
    private RedisMessageListenerContainer container;

    @PostConstruct
    void subscribe() {
        if (!messagingEnabled) {
            log.info("未启用 family.messaging，通话信令为单实例投递");
            return;
        }
        redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.info("未启用 Redis，通话信令降级为单实例投递");
            return;
        }
        RedisConnectionFactory factory = redis.getConnectionFactory();
        if (factory == null) return;
        container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(
                (message, pattern) -> onRemote(new String(message.getBody())),
                new ChannelTopic(CHANNEL));
        container.afterPropertiesSet();
        container.start();
    }

    @PreDestroy
    void shutdown() {
        if (container != null) container.stop();
    }

    /** 广播给房间内所有参与者（服务端已展开收件人）。 */
    public void broadcast(
            String type, String callId, long householdId, long from, List<Long> recipients, Map<String, Object> data) {
        send(new CallSignal(type, callId, from, householdId, recipients, data));
    }

    /** 点对点投递，用于 SDP 与 ICE 候选。 */
    public void toUser(
            String type, String callId, long householdId, long from, long to, Map<String, Object> data) {
        send(new CallSignal(type, callId, from, householdId, List.of(to), data));
    }

    public void send(CallSignal signal) {
        deliverLocal(signal);
        StringRedisTemplate template = redis;
        if (template == null) return;
        try {
            template.convertAndSend(CHANNEL, mapper.writeValueAsString(new Envelope(instanceId, signal)));
        } catch (Exception ex) {
            log.warn("通话信令广播失败 callId={} type={}", signal.callId(), signal.type(), ex);
        }
    }

    private void onRemote(String body) {
        try {
            Envelope envelope = mapper.readValue(body, Envelope.class);
            if (envelope == null || envelope.signal() == null) return;
            if (instanceId.equals(envelope.origin())) return; // 本机已投递过，避免重复
            deliverLocal(envelope.signal());
        } catch (Exception ex) {
            log.warn("通话信令解析失败，已丢弃", ex);
        }
    }

    private void deliverLocal(CallSignal signal) {
        CallSocket socket = socketProvider.getIfAvailable();
        if (socket == null) return;
        try {
            socket.deliver(signal);
        } catch (Exception ex) {
            log.warn("通话信令本机投递失败 callId={} type={}", signal.callId(), signal.type(), ex);
        }
    }
}
