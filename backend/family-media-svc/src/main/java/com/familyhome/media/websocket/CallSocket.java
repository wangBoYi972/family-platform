package com.familyhome.media.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyhome.media.config.MediaProperties;
import com.familyhome.media.service.CallService;
import com.familyhome.media.service.CallSignal;
import com.familyhome.media.service.CallSignalBus;
import com.familyhome.messaging.RedisTicketStore;
import com.familyhome.security.SessionPrincipal;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 通话信令通道 {@code /ws/call}。
 *
 * <p>鉴权走一次性 ticket：REST 侧用 JWT 换取 30 秒有效、用后即焚的票据，
 * 浏览器的 WebSocket 无法自定义请求头，因此不把 JWT 放进 URL（会被代理日志记录）。
 *
 * <p>本类只做“连接管理 + 协议解析 + 转发”，状态机与权限在 {@link CallService}。
 */
@Configuration
@EnableWebSocket
public class CallSocket extends TextWebSocketHandler implements WebSocketConfigurer {
    private static final Logger log = LoggerFactory.getLogger(CallSocket.class);
    private static final int MAX_SESSIONS_PER_USER = 5;
    private static final long HEARTBEAT_TIMEOUT_MS = 45_000L; // 45秒：缩短异常连接占用时间
    private static final int MAX_MESSAGES_PER_MINUTE = 600;

    private record Ticket(SessionPrincipal principal, long expiry) {}

    private record PendingLeave(long householdId, long deadline) {}

    private static final class Connection {
        final WebSocketSession session;
        final SessionPrincipal principal;
        final AtomicInteger windowCount = new AtomicInteger();
        volatile long windowStart = System.currentTimeMillis();
        volatile long lastSeen = System.currentTimeMillis();

        Connection(WebSocketSession session, SessionPrincipal principal) {
            this.session = session;
            this.principal = principal;
        }
    }

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> byUser = new ConcurrentHashMap<>();
    /** userId -> 宽限期；断线后不立即退出通话。 */
    private final Map<Long, PendingLeave> pendingLeave = new ConcurrentHashMap<>();
    /** Ticket 清理时间窗口，避免每次请求都遍历整个 Map */
    private volatile long lastTicketCleanupTime = 0;
    private static final long TICKET_CLEANUP_INTERVAL_MS = 5_000L; // 5秒清理一次

    @Autowired private ObjectMapper mapper;
    @Autowired private CallService calls;
    @Autowired private CallSignalBus bus;
    @Autowired private MediaProperties properties;
    @Autowired private ObjectProvider<RedisTicketStore> ticketStoreProvider;

    private RedisTicketStore ticketStore;

    @PostConstruct
    void init() {
        ticketStore = ticketStoreProvider.getIfAvailable();
    }

    public String issueTicket(SessionPrincipal principal) {
        if (ticketStore != null) return ticketStore.issue(principal);

        long now = System.currentTimeMillis();
        // 定时清理过期 ticket，避免每次请求都遍历
        if (now - lastTicketCleanupTime > TICKET_CLEANUP_INTERVAL_MS) {
            tickets.entrySet().removeIf(entry -> entry.getValue().expiry() < now);
            lastTicketCleanupTime = now;
        }

        if (tickets.size() > 2000) {
            // 强制清理后再检查
            tickets.entrySet().removeIf(entry -> entry.getValue().expiry() < now);
            if (tickets.size() > 2000) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "系统繁忙，请稍后再试");
            }
        }

        String id = UUID.randomUUID().toString();
        tickets.put(id, new Ticket(principal, now + 30_000));
        return id;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(this, "/ws/call")
                .addInterceptors(
                        new HandshakeInterceptor() {
                            @Override
                            public boolean beforeHandshake(
                                    ServerHttpRequest request,
                                    ServerHttpResponse response,
                                    WebSocketHandler handler,
                                    Map<String, Object> attributes) {
                                String ticket =
                                        UriComponentsBuilder.fromUri(request.getURI())
                                                .build()
                                                .getQueryParams()
                                                .getFirst("ticket");
                                SessionPrincipal principal = null;
                                if (ticketStore != null) principal = ticketStore.consume(ticket);
                                else {
                                    Ticket stored = ticket == null ? null : tickets.remove(ticket);
                                    if (stored != null && stored.expiry() >= System.currentTimeMillis())
                                        principal = stored.principal();
                                }
                                if (principal == null) return false;
                                attributes.put("principal", principal);
                                attributes.put("householdId", principal.householdId());
                                attributes.put("userId", principal.userId());
                                return true;
                            }

                            @Override
                            public void afterHandshake(
                                    ServerHttpRequest request,
                                    ServerHttpResponse response,
                                    WebSocketHandler handler,
                                    Exception exception) {}
                        })
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        SessionPrincipal principal = principal(session);
        if (principal == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        Set<String> mine = byUser.computeIfAbsent(principal.userId(), key -> ConcurrentHashMap.newKeySet());
        if (mine.size() >= MAX_SESSIONS_PER_USER) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("同一账号连接过多"));
            return;
        }
        mine.add(session.getId());
        connections.put(session.getId(), new Connection(session, principal));
        pendingLeave.remove(principal.userId());
        send(session, Map.of("type", "ready", "userId", principal.userId()));
        log.debug("通话信令连接建立 userId={} session={}", principal.userId(), session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Connection connection = connections.get(session.getId());
        if (connection == null) return;
        connection.lastSeen = System.currentTimeMillis();
        if (!allow(connection)) {
            sendError(session, 429, "信令过于频繁，请稍后重试");
            return;
        }
        if (message.getPayloadLength() > 32 * 1024) {
            sendError(session, 413, "信令内容过大");
            return;
        }

        Map<String, Object> payload;
        try {
            payload = mapper.readValue(message.getPayload(), mapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception ex) {
            sendError(session, 400, "信令格式错误");
            return;
        }
        String type = text(payload.get("type"));
        String callId = text(payload.get("callId"));
        if (type == null) {
            sendError(session, 400, "缺少信令类型");
            return;
        }
        if ("ping".equals(type)) {
            send(session, Map.of("type", "pong", "ts", System.currentTimeMillis()));
            return;
        }
        try {
            route(connection, type, callId, payload);
        } catch (ResponseStatusException ex) {
            sendError(session, ex.getStatusCode().value(), ex.getReason());
        } catch (Exception ex) {
            log.warn("信令处理失败 type={} callId={}", type, callId, ex);
            sendError(session, 500, "信令处理失败");
        }
    }

    private void route(Connection connection, String type, String callId, Map<String, Object> payload) {
        if (callId == null || callId.isBlank()) throw badRequest("缺少 callId");
        SessionPrincipal principal = connection.principal;
        long self = principal.userId();

        switch (type) {
            case "call.answer" -> calls.answer(principal, callId);
            case "call.reject" -> calls.reject(principal, callId);
            case "call.leave" -> calls.leave(principal, callId);
            case "call.end" -> calls.end(principal, callId);
            case "call.state" ->
                    calls.updateMediaState(
                            principal, callId, bool(payload.get("mic")), bool(payload.get("cam")));
            // 录音状态必须由服务端广播：客户端各自翻转本地状态的话，
            // 别人永远看不到「他正在录我」
            case "call.record" -> calls.setRecording(principal, callId, bool(payload.get("on")));
            case "webrtc.offer", "webrtc.answer" -> {
                String sdp = text(payload.get("sdp"));
                Long to = number(payload.get("to"));
                if (sdp == null || sdp.isBlank() || to == null) throw badRequest("缺少 sdp 或收件人");
                requireSignal(callId, self, to);
                bus.toUser(
                        type,
                        callId,
                        principal.householdId(),
                        self,
                        to,
                        Map.of("sdp", sdp, "from", self));
            }
            case "webrtc.ice" -> {
                Object candidate = payload.get("candidate");
                Long to = number(payload.get("to"));
                if (candidate == null || to == null) throw badRequest("缺少 candidate 或收件人");
                requireSignal(callId, self, to);
                bus.toUser(
                        type,
                        callId,
                        principal.householdId(),
                        self,
                        to,
                        Map.of("candidate", candidate, "from", self));
            }
            case "call.heartbeat" -> {
                if (calls.canSignal(callId, self))
                    send(connection.session, Map.of("type", "call.heartbeat", "callId", callId));
            }
            default -> sendError(connection.session, 400, "未知信令类型: " + type);
        }
    }

    private void requireSignal(String callId, long self, long to) {
        if (!calls.canSignal(callId, self)) throw forbidden("你不在该通话中");
        if (!calls.canSignal(callId, to)) throw forbidden("对方不在该通话中");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Connection connection = connections.remove(session.getId());
        if (connection == null) return;
        long userId = connection.principal.userId();
        Set<String> mine = byUser.get(userId);
        if (mine != null) {
            mine.remove(session.getId());
            if (mine.isEmpty()) byUser.remove(userId);
        }
        if (mine == null || mine.isEmpty()) {
            long grace = Math.max(5, properties.getReconnectGraceSeconds()) * 1000L;
            pendingLeave.put(
                    userId,
                    new PendingLeave(
                            connection.principal.householdId(), System.currentTimeMillis() + grace));
        }
        log.debug("通话信令连接关闭 userId={} status={}", userId, status);
    }

    /** 投递信令到本机连接；跨实例由 {@code CallSignalBus} 负责。 */
    public void deliver(CallSignal signal) {
        if (signal.to() == null || signal.to().isEmpty()) return;
        for (Long userId : signal.to()) {
            Set<String> ids = byUser.get(userId);
            if (ids == null) continue;
            for (String id : ids) {
                Connection connection = connections.get(id);
                if (connection == null) continue;
                if (!Objects.equals(connection.principal.householdId(), signal.householdId()))
                    continue;
                send(connection.session, envelope(signal));
            }
        }
    }

    private Map<String, Object> envelope(CallSignal signal) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", signal.type());
        if (signal.callId() != null) message.put("callId", signal.callId());
        if (signal.from() != null) message.put("from", signal.from());
        if (signal.data() != null) {
            signal.data().forEach((key, value) -> {
                if (value != null) message.putIfAbsent(key, value);
            });
        }
        return message;
    }

    /** 清理心跳超时的连接；连接关闭后会进入宽限期，不会误判为挂断。 */
    @Scheduled(fixedDelay = 20_000L)
    public void reapIdle() {
        long now = System.currentTimeMillis();
        for (Connection connection : connections.values()) {
            if (now - connection.lastSeen < HEARTBEAT_TIMEOUT_MS) continue;
            try {
                connection.session.close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException ignored) {
                // 关闭失败说明连接已经不可用，交给容器回收
            }
        }
    }

    /** 宽限期结束仍未重连，才真正退出通话。 */
    @Scheduled(fixedDelay = 5_000L)
    public void flushPendingLeave() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, PendingLeave> entry : pendingLeave.entrySet()) {
            long userId = entry.getKey();
            if (byUser.containsKey(userId)) {
                pendingLeave.remove(userId);
                continue;
            }
            if (entry.getValue().deadline() > now) continue;
            pendingLeave.remove(userId);
            try {
                calls.leaveIfDisconnected(userId, entry.getValue().householdId());
            } catch (RuntimeException ex) {
                log.warn("断线自动离开通话失败 userId={}", userId, ex);
            }
        }
    }

    private boolean allow(Connection connection) {
        long now = System.currentTimeMillis();
        if (now - connection.windowStart > 60_000L) {
            connection.windowStart = now;
            connection.windowCount.set(0);
            return true;
        }
        return connection.windowCount.incrementAndGet() <= MAX_MESSAGES_PER_MINUTE;
    }

    private SessionPrincipal principal(WebSocketSession session) {
        Object value = session.getAttributes().get("principal");
        return value instanceof SessionPrincipal principal ? principal : null;
    }

    private void send(WebSocketSession session, Map<String, Object> payload) {
        if (session == null || !session.isOpen()) return;
        try {
            String json = mapper.writeValueAsString(payload);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception ex) {
            log.debug("信令发送失败 userId={}", session.getAttributes().get("userId"), ex);
        }
    }

    private void sendError(WebSocketSession session, int code, String message) {
        send(session, Map.of("type", "error", "code", code, "message", message == null ? "信令失败" : message));
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static Long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
