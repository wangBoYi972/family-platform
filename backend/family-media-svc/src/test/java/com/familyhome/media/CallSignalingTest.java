package com.familyhome.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyhome.media.dto.CallStartDTO;
import com.familyhome.media.service.CallService;
import com.familyhome.media.websocket.CallSocket;
import com.familyhome.security.IdentityVerifier;
import com.familyhome.security.SessionPrincipal;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 真实 WebSocket 链路的端到端验证：票据握手、来电推送、接听广播、SDP 转发与越权拦截。
 *
 * <p>用本地 IdentityVerifier 替身替代用户服务，避免测试依赖外部进程。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:call-ws-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.cloud.nacos.discovery.enabled=false",
            "family.messaging.enabled=false",
            "family.media.verify-members=false"
        })
class CallSignalingTest {
    @TestConfiguration
    static class LocalVerifier {
        @Bean
        IdentityVerifier identityVerifier() {
            return principal -> {};
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("FAMILY_JWT_SECRET", () -> UUID.randomUUID() + UUID.randomUUID().toString());
    }

    @LocalServerPort private int port;
    @Autowired private CallService calls;
    @Autowired private CallSocket socket;
    @Autowired private ObjectMapper mapper;

    private final SessionPrincipal caller = new SessionPrincipal(21, 301, "MEMBER", "c1", "family");
    private final SessionPrincipal callee = new SessionPrincipal(22, 301, "MEMBER", "c2", "family");

    @Test
    @DisplayName("来电推送、接听广播、SDP 转发与越权拒绝")
    void signalsAcrossRealWebSocket() throws Exception {
        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocketSession session = connect(callee, inbox);

        var created =
                calls.start(caller, null, new CallStartDTO("DIRECT", "AUDIO", callee.userId(), null));

        Map<String, Object> invited = awaitType(inbox, "call.invited");
        assertThat(invited.get("callId")).isEqualTo(created.id());
        assertThat(invited.get("from")).isEqualTo((int) caller.userId());

        session.sendMessage(new TextMessage(json(Map.of("type", "call.answer", "callId", created.id()))));
        Map<String, Object> accepted = awaitType(inbox, "call.accepted");
        assertThat(accepted.get("user")).isEqualTo((int) callee.userId());

        // 合法转发：收件人是本通话成员，服务端只做中继，不应回错误
        session.sendMessage(
                new TextMessage(
                        json(
                                Map.of(
                                        "type", "webrtc.offer",
                                        "callId", created.id(),
                                        "to", caller.userId(),
                                        "sdp", "v=0"))));
        // 越权转发：收件人不在通话中，必须被拒绝
        session.sendMessage(
                new TextMessage(
                        json(
                                Map.of(
                                        "type", "webrtc.ice",
                                        "callId", created.id(),
                                        "to", 999,
                                        "candidate", Map.of("candidate", "candidate:1 1 UDP 1 127.0.0.1 5000 typ host")))));

        Map<String, Object> error = awaitType(inbox, "error");
        assertThat(error.get("code")).isEqualTo(403);

        session.close();
    }

    @Test
    @DisplayName("无效票据无法建立连接")
    void rejectsUnknownTicket() {
        var client = new StandardWebSocketClient();
        var handler = new TextWebSocketHandler() {};
        assertThat(
                        client.execute(
                                        handler,
                                        "ws://127.0.0.1:" + port + "/ws/call?ticket=not-a-real-ticket")
                                .orTimeout(5, TimeUnit.SECONDS)
                                .handle((value, error) -> error != null)
                                .join())
                .isTrue();
    }

    private WebSocketSession connect(SessionPrincipal principal, BlockingQueue<String> inbox)
            throws Exception {
        String ticket = socket.issueTicket(principal);
        var client = new StandardWebSocketClient();
        var handler =
                new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                        inbox.add(message.getPayload());
                    }
                };
        WebSocketSession session =
                client.execute(handler, "ws://127.0.0.1:" + port + "/ws/call?ticket=" + ticket)
                        .get(5, TimeUnit.SECONDS);
        awaitType(inbox, "ready");
        return session;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitType(BlockingQueue<String> inbox, String type) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        while (System.currentTimeMillis() < deadline) {
            String raw = inbox.poll(200, TimeUnit.MILLISECONDS);
            if (raw == null) continue;
            Map<String, Object> message = mapper.readValue(raw, LinkedHashMap.class);
            if (type.equals(message.get("type"))) return message;
        }
        throw new AssertionError("等待信令超时: " + type);
    }

    private String json(Map<String, ?> payload) throws Exception {
        return mapper.writeValueAsString(payload);
    }
}
