package com.familyhome.im;

import static org.assertj.core.api.Assertions.assertThat;

import com.familyhome.api.file.feign.FileFeignClient;
import com.familyhome.api.user.feign.UserFeignClient;
import com.familyhome.im.websocket.ImSocket;
import com.familyhome.security.SessionPrincipal;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 实时刷新通道的端到端验证：换票 → 握手 → 收到 {@code {"type":"refresh"}}。
 *
 * <p>前端 {@code family-socket.js} 依赖的正是这条契约：票据在 URL 里、消息体只要 type=refresh。
 * 一旦有人改了握手参数名或广播内容，这里会先红，而不是等用户发现「另一台设备不刷新」。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:im-ws-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.cloud.nacos.discovery.enabled=false",
            "family.messaging.enabled=false"
        })
@ActiveProfiles("local")
class ImSocketTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("FAMILY_JWT_SECRET", () -> UUID.randomUUID().toString() + UUID.randomUUID());
    }

    @Autowired ImSocket socket;
    @LocalServerPort int port;
    @MockBean UserFeignClient users;
    @MockBean FileFeignClient files;

    @Test
    @DisplayName("家庭内连接能收到刷新广播，别的家庭收不到")
    void refreshBroadcastReachesOnlyOwnHousehold() throws Exception {
        var mine = new SessionPrincipal(1, 10, "MEMBER", "test", "family");
        var stranger = new SessionPrincipal(2, 20, "MEMBER", "test", "family");

        var myMessages = connect(socket.ticket(mine));
        var strangerMessages = connect(socket.ticket(stranger));

        socket.notifyFamily(mine.householdId());

        assertThat(poll(myMessages)).contains("{\"type\":\"refresh\"}");
        // 广播按家庭隔离：不能靠「连上就收到全站消息」糊过去
        assertThat(strangerMessages.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("新消息以结构化帧下发，客户端收到即可上屏，不必再回查接口")
    void messageFrameCarriesContent() throws Exception {
        var mine = new SessionPrincipal(1, 10, "MEMBER", "test", "family");
        var queue = connect(socket.ticket(mine));

        socket.notifyFamilyEvent(
                mine.householdId(),
                java.util.Map.of(
                        "type", "message",
                        "conversationId", "dm-10-1-2",
                        "messageId", 7,
                        "senderId", 1,
                        "kind", "TEXT",
                        "body", "到家了",
                        "createdAt", "2026-11-01T09:00:00"));

        String frame = poll(queue);
        // 帧里必须有渲染所需的一切：只发一个 id 等于让客户端再拉一次，白搭一条推送
        assertThat(frame)
                .contains("\"type\":\"message\"")
                .contains("\"conversationId\":\"dm-10-1-2\"")
                .contains("\"messageId\":7")
                .contains("\"body\":\"到家了\"");
    }

    private BlockingQueue<String> connect(String ticket) throws Exception {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        WebSocketSession session =
                new StandardWebSocketClient()
                        .execute(
                                new TextWebSocketHandler() {
                                    @Override
                                    protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                                        queue.add(message.getPayload());
                                    }
                                },
                                URI.create("ws://127.0.0.1:" + port + "/ws/im?ticket=" + ticket).toString())
                        .get(5, TimeUnit.SECONDS);
        // 连接关掉后服务端才摘 session，测试结束前留个引用避免被回收
        sessions.add(session);
        return queue;
    }

    private final List<WebSocketSession> sessions = new java.util.ArrayList<>();

    private String poll(BlockingQueue<String> queue) throws InterruptedException {
        return queue.poll(5, TimeUnit.SECONDS);
    }
}
