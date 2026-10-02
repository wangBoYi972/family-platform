package com.familyhome.im.websocket;

import com.familyhome.security.SessionPrincipal;
import com.familyhome.messaging.RedisOnlineConnectionRegistry;
import com.familyhome.messaging.RedisTicketStore;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private record Ticket(SessionPrincipal principal, long expiry) {}

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> onlineMembers = new ConcurrentHashMap<>();
    @Autowired private ObjectProvider<RedisTicketStore> ticketStoreProvider;
    @Autowired private ObjectProvider<RedisOnlineConnectionRegistry> onlineRegistryProvider;
    @Autowired private ObjectProvider<RedisConnectionFactory> redisConnectionFactoryProvider;
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
        container.addMessageListener((message, pattern) -> notifyLocal(Long.parseLong(new String(message.getBody()))), new ChannelTopic("family:ws:broadcast"));
        container.afterPropertiesSet();
        container.start();
    }

    public String ticket(SessionPrincipal p) {
        if (ticketStore != null) return ticketStore.issue(p);
        tickets.entrySet().removeIf(e -> e.getValue().expiry < System.currentTimeMillis());
        if (tickets.size() > 1000) throw new IllegalStateException("连接过多");
        String id = UUID.randomUUID().toString();
        tickets.put(id, new Ticket(p, System.currentTimeMillis() + 30000));
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
                                if (principal == null && ticket != null && ticket.expiry >= System.currentTimeMillis()) principal = ticket.principal;
                                if (principal == null) return false;
                                attributes.put("householdId", principal.householdId());
                                return true;
                            }

                            public void afterHandshake(
                                    ServerHttpRequest request,
                                    ServerHttpResponse response,
                                    WebSocketHandler handler,
                                    Exception exception) {}
                        })
                .setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        Object household = session.getAttributes().get("householdId");
        if (onlineRegistry != null && household instanceof Number n)
            onlineMembers.put(session.getId(), onlineRegistry.register(n.longValue(), session.getId()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        Object household = session.getAttributes().get("householdId");
        String member = onlineMembers.remove(session.getId());
        if (onlineRegistry != null && household instanceof Number n) onlineRegistry.unregister(n.longValue(), member);
    }

    public void notifyFamily(long household) {
        if (onlineRegistry != null) onlineRegistry.broadcast(household);
        notifyLocal(household);
    }

    private void notifyLocal(long household) {
        for (var s : sessions.values())
            if (Objects.equals(s.getAttributes().get("householdId"), household) && s.isOpen()) {
                try {
                    synchronized (s) {
                        s.sendMessage(new TextMessage("{\"type\":\"refresh\"}"));
                    }
                } catch (Exception ex) {
                    sessions.remove(s.getId());
                }
            }
    }

    public void notifyLocalEvent(long household) { notifyLocal(household); }
}
