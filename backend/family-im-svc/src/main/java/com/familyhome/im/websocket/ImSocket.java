package com.familyhome.im.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyhome.security.SessionPrincipal;
import com.familyhome.messaging.RedisOnlineConnectionRegistry;
import com.familyhome.messaging.RedisTicketStore;
import jakarta.annotation.PostConstruct;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration
@EnableWebSocket
public class ImSocket extends TextWebSocketHandler implements WebSocketConfigurer {
    private static final Logger log = LoggerFactory.getLogger(ImSocket.class);
    /** 只带家庭号的广播：收到就重拉一次数据。 */
    private static final String REFRESH_CHANNEL = "family:ws:broadcast";
    /** 带结构化帧的广播：多实例部署时其它实例上的连接也要拿到同样的内容。 */
    private static final String EVENT_CHANNEL = "family:ws:event";
    private static final String REFRESH_FRAME = "{\"type\":\"refresh\"}";

    private record Ticket(SessionPrincipal principal, long expiry) {
    }

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> onlineMembers = new ConcurrentHashMap<>();
    /** Ticket 清理时间窗口，避免每次请求都遍历整个 Map */
    private volatile long lastTicketCleanupTime = 0;
    private static final long TICKET_CLEANUP_INTERVAL_MS = 5_000L; // 5秒清理一次

    @Autowired
    private ObjectProvider<RedisTicketStore> ticketStoreProvider;
    @Autowired
    private ObjectProvider<RedisOnlineConnectionRegistry> onlineRegistryProvider;
    @Autowired
    private ObjectProvider<RedisConnectionFactory> redisConnectionFactoryProvider;
    @Autowired
    private ObjectMapper mapper;
    /**
     * 握手来源白名单。原来硬编码成 localhost，本地开发没问题，一上线浏览器带真实域名
     * 就会被 403 挡掉、刷新事件永远收不到。生产用 FAMILY_IM_ALLOWED_ORIGINS 覆盖成自己的域名。
     */
    @Value("${family.im.allowed-origins:http://localhost:*,http://127.0.0.1:*}")
    private String[] allowedOrigins;
    private RedisTicketStore ticketStore;
    private RedisOnlineConnectionRegistry onlineRegistry;
    private RedisConnectionFactory redisConnectionFactory;

    @PostConstruct
    void subscribeBroadcasts() {
        ticketStore = ticketStoreProvider.getIfAvailable();
        onlineRegistry = onlineRegistryProvider.getIfAvailable();
        redisConnectionFactory = redisConnectionFactoryProvider.getIfAvailable();
        if (onlineRegistry == null || redisConnectionFactory == null) return;
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.addMessageListener((message, pattern) -> notifyLocal(Long.parseLong(new String(message.getBody()))), new ChannelTopic(REFRESH_CHANNEL));
        container.addMessageListener((message, pattern) -> relayEvent(new String(message.getBody())), new ChannelTopic(EVENT_CHANNEL));
        container.afterPropertiesSet();
        container.start();
    }

    /** 其它实例转发过来的结构化帧：{@code {"householdId":10,"audience":[1,2],"frame":{"type":"message",...}}}。 */
    private void relayEvent(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            long household = node.path("householdId").asLong();
            JsonNode frame = node.path("frame");
            if (household > 0 && !frame.isMissingNode())
                sendLocal(household, frame.toString(), audienceOf(node.path("audience")));
        } catch (Exception ex) {
            // 单条坏帧丢掉就好，不能让监听线程挂掉——那会导致这台实例再也收不到任何广播
            log.warn("实时帧转发解析失败，已丢弃：{}", ex.getMessage());
        }
    }

    /** 信封里的收件人列表；空或缺省表示整户广播。 */
    private static Set<Long> audienceOf(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) return null;
        Set<Long> targets = new HashSet<>();
        node.forEach(item -> targets.add(item.asLong()));
        return targets.isEmpty() ? null : targets;
    }

    public String ticket(SessionPrincipal p) {
        if (ticketStore != null) return ticketStore.issue(p);

        long now = System.currentTimeMillis();
        // 定时清理过期 ticket，避免每次请求都遍历
        if (now - lastTicketCleanupTime > TICKET_CLEANUP_INTERVAL_MS) {
            tickets.entrySet().removeIf(e -> e.getValue().expiry < now);
            lastTicketCleanupTime = now;
        }

        if (tickets.size() > 1000) {
            // 强制清理后再检查
            tickets.entrySet().removeIf(e -> e.getValue().expiry < now);
            if (tickets.size() > 1000) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "系统繁忙，请稍后再试");
            }
        }

        String id = UUID.randomUUID().toString();
        tickets.put(id, new Ticket(p, now + 30000));
        return id;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry
            .addHandler(this, "/ws/im")
            .addInterceptors(
                new HandshakeInterceptor() {
                    public boolean beforeHandshake(
                        ServerHttpRequest request,
                        ServerHttpResponse response,
                        WebSocketHandler handler,
                        Map<String, Object> attributes) {
                        String key =
                            UriComponentsBuilder.fromUri(request.getURI())
                                .build()
                                .getQueryParams()
                                .getFirst("ticket");
                        SessionPrincipal principal = ticketStore == null ? null : ticketStore.consume(key);
                        Ticket ticket = ticketStore == null && key != null ? tickets.remove(key) : null;
                        if (principal == null && ticket != null && ticket.expiry >= System.currentTimeMillis())
                            principal = ticket.principal;
                        if (principal == null) return false;
                        attributes.put("householdId", principal.householdId());
                        // 定向投递要靠 userId 认人：私聊的帧只该落到会话双方的连接上
                        attributes.put("userId", principal.userId());
                        return true;
                    }

                    public void afterHandshake(
                        ServerHttpRequest request,
                        ServerHttpResponse response,
                        WebSocketHandler handler,
                        Exception exception) {
                    }
                })
            .setAllowedOriginPatterns(allowedOrigins);
    }

    /** 当前本机登记的连接数：测试用来等握手完成（连接登记是异步的），生产无调用。 */
    public int sessionCount() {
        return sessions.size();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        Object household = session.getAttributes().get("householdId");
        Object userId = session.getAttributes().get("userId");
        if (onlineRegistry != null && household instanceof Number n) {
            String member = onlineRegistry.register(n.longValue(), session.getId());
            onlineMembers.put(session.getId(), member);
            // 按用户的在线标记：离线推送（微信订阅消息）靠它判断该不该发
            if (userId instanceof Number u) onlineRegistry.registerUser(u.longValue(), member);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        Object household = session.getAttributes().get("householdId");
        Object userId = session.getAttributes().get("userId");
        String member = onlineMembers.remove(session.getId());
        if (onlineRegistry != null && household instanceof Number n) {
            onlineRegistry.unregister(n.longValue(), member);
            if (userId instanceof Number u && member != null) onlineRegistry.unregisterUser(u.longValue(), member);
        }
    }

    public void notifyFamily(long household) {
        if (onlineRegistry != null) onlineRegistry.broadcast(household);
        notifyLocal(household);
    }

    /**
     * 向同家庭的所有连接推送一条结构化帧（如新消息），多实例下由 Redis 转发。
     *
     * <p>帧里已经带齐客户端要用的字段，收到就能直接上屏，不用再回查一次接口——
     * 这也是消息推送相比「收到信号再拉一次」的唯一优势。
     */
    public void notifyFamilyEvent(long household, Map<String, Object> frame) {
        notifyFamilyEvent(household, frame, null);
    }

    /**
     * 只把帧投给 {@code audience} 里的成员；{@code null} 或空表示整户广播。
     *
     * <p>私聊的消息正文不该出现在全家每台设备上：客户端只是「不渲染」，帧本身还是一条条
     * 明文躺在别人的连接里。定向投递让「只看得到自己会话」成为服务端保证。
     */
    public void notifyFamilyEvent(long household, Map<String, Object> frame, Collection<Long> audience) {
        String json;
        try {
            json = mapper.writeValueAsString(frame);
        } catch (Exception ex) {
            // 序列化不出来就退回刷新，至少不会静默丢通知
            log.warn("实时帧序列化失败，退化为刷新 type={}", frame.get("type"));
            json = REFRESH_FRAME;
        }
        Set<Long> targets = audience == null || audience.isEmpty() ? null : new HashSet<>(audience);
        if (onlineRegistry != null) onlineRegistry.broadcast(household, json, targets);
        sendLocal(household, json, targets);
    }

    /** 发给本机连接的刷新信号（兼容旧调用）。 */
    public void notifyLocalEvent(long household) {
        notifyLocal(household);
    }

    private void notifyLocal(long household) {
        sendLocal(household, REFRESH_FRAME, null);
    }

    private void sendLocal(long household, String frame, Set<Long> targets) {
        for (var s : sessions.values()) {
            if (!Objects.equals(s.getAttributes().get("householdId"), household) || !s.isOpen()) continue;
            // 定向帧发不出去宁可漏一条，也不能发错人：认不出身份就跳过
            if (targets != null && !targets.contains(userId(s))) continue;
            try {
                synchronized (s) {
                    s.sendMessage(new TextMessage(frame));
                }
            } catch (Exception ex) {
                sessions.remove(s.getId());
            }
        }
    }

    private static Long userId(WebSocketSession session) {
        Object value = session.getAttributes().get("userId");
        return value instanceof Number n ? n.longValue() : null;
    }
}
