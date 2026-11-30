package com.familyhome.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
@ConditionalOnProperty(name = "family.messaging.enabled", havingValue = "true")
public class RedisOnlineConnectionRegistry {
    /** 只带家庭号的广播，收到就重拉数据。 */
    private static final String REFRESH_CHANNEL = "family:ws:broadcast";
    /** 带结构化帧的广播（如新消息），多实例部署时其它实例上的连接也要收到同一份内容。 */
    private static final String EVENT_CHANNEL = "family:ws:event";

    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper mapper;
    private final String instanceId = UUID.randomUUID().toString();
    public String register(long householdId, String sessionId) {
        String member = instanceId + ":" + sessionId;
        redis.opsForSet().add("family:online:" + householdId, member);
        redis.expire("family:online:" + householdId, Duration.ofMinutes(2));
        return member;
    }
    public void touch(long householdId, String member) { redis.opsForSet().add("family:online:" + householdId, member); redis.expire("family:online:" + householdId, Duration.ofMinutes(2)); }
    public void unregister(long householdId, String member) { redis.opsForSet().remove("family:online:" + householdId, member); }

    /**
     * 按用户的在线标记：离线推送（微信订阅消息）只发给不在线的人。
     * 同一个 key 结构（成员集合 + 2 分钟过期），靠心跳维持。
     */
    public void registerUser(long userId, String member) {
        redis.opsForSet().add("family:online-user:" + userId, member);
        redis.expire("family:online-user:" + userId, Duration.ofMinutes(2));
    }

    public void unregisterUser(long userId, String member) {
        redis.opsForSet().remove("family:online-user:" + userId, member);
    }

    /** 用户是否至少有一条活连接；空集合在 Redis 里自动消失，size 判断就够。 */
    public boolean isUserOnline(long userId) {
        Long size = redis.opsForSet().size("family:online-user:" + userId);
        return size != null && size > 0;
    }

    public void broadcast(long householdId) { redis.convertAndSend(REFRESH_CHANNEL, String.valueOf(householdId)); }

    /**
     * 把已经序列化好的帧转发给其它实例。
     *
     * <p>注意这里给的是「帧 JSON 字符串」而不是对象：调用方已经序列化过一次，
     * 再传对象就得在两边各写一份同样的 DTO，加字段时必漏一边。
     */
    public void broadcast(long householdId, String frameJson) {
        broadcast(householdId, frameJson, null);
    }

    /**
     * 定向转发：信封里带上收件人，其它实例据此只投给自己的那几条连接。
     *
     * <p>{@code audience} 为 {@code null}/空表示整户广播。列表本身也走信封而不是另开通道，
     * 这样「谁该收到」只在一处判断，本地直发和跨实例转发不会得出不同答案。
     */
    public void broadcast(long householdId, String frameJson, Collection<Long> audience) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("householdId", householdId);
            if (audience != null && !audience.isEmpty()) envelope.put("audience", audience);
            envelope.put("frame", mapper.readTree(frameJson));
            redis.convertAndSend(EVENT_CHANNEL, mapper.writeValueAsString(envelope));
        } catch (Exception ex) {
            // 结构化帧发不出去就退回普通刷新：宁可多拉一次，也别让家人以为消息没发出去
            broadcast(householdId);
        }
    }
}
