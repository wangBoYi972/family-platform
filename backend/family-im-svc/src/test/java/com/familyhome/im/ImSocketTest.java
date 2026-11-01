package com.familyhome.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.familyhome.api.file.feign.FileFeignClient;
import com.familyhome.api.user.feign.UserFeignClient;
import com.familyhome.im.dto.ConversationCreateDTO;
import com.familyhome.im.dto.MessageReadDTO;
import com.familyhome.im.dto.MessageSendDTO;
import com.familyhome.im.service.ImService;
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
    @Autowired ImService service;
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
    @DisplayName("领域事件触发的刷新帧带上事件类型，客户端据此只重拉受影响的面板")
    void refreshFrameCarriesEventType() throws Exception {
        var mine = new SessionPrincipal(1, 10, "MEMBER", "test", "family");
        var queue = connect(socket.ticket(mine));

        socket.notifyFamilyEvent(
                mine.householdId(),
                java.util.Map.of("type", "refresh", "event", "task.created"));

        String frame = poll(queue);
        assertThat(frame)
                .contains("\"type\":\"refresh\"")
                .contains("\"event\":\"task.created\"");
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

    @Test
    @DisplayName("定向帧只投给收件人，同家庭的其他连接收不到")
    void targetedFrameSkipsOtherMembers() throws Exception {
        var sender = new SessionPrincipal(1, 10, "MEMBER", "test", "family");
        var peer = new SessionPrincipal(2, 10, "MEMBER", "test", "family");
        var bystander = new SessionPrincipal(3, 10, "MEMBER", "test", "family");

        var senderQueue = connect(socket.ticket(sender));
        var peerQueue = connect(socket.ticket(peer));
        var bystanderQueue = connect(socket.ticket(bystander));

        socket.notifyFamilyEvent(
                sender.householdId(),
                java.util.Map.of("type", "message", "conversationId", "dm-10-1-2", "messageId", 9),
                java.util.List.of(sender.userId(), peer.userId()));

        // 私聊正文只能到会话双方手里：同住一个家庭不等于能看别人的私聊
        assertThat(poll(senderQueue)).contains("\"messageId\":9");
        assertThat(poll(peerQueue)).contains("\"messageId\":9");
        assertThat(bystanderQueue.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("已读回执实时下发：双方各收到一次，同家庭第三者收不到")
    void readReceiptIsPushedToParticipantsOnly() throws Exception {
        var a = new SessionPrincipal(1, 10, "MEMBER", "test", "family");
        var b = new SessionPrincipal(2, 10, "MEMBER", "test", "family");
        var bystander = new SessionPrincipal(3, 10, "MEMBER", "test", "family");
        var aQueue = connect(socket.ticket(a));
        var bQueue = connect(socket.ticket(b));
        var bystanderQueue = connect(socket.ticket(bystander));

        when(users.members("token"))
                .thenReturn(
                        com.familyhome.common.api.ApiResponse.success(
                                List.of(
                                        new com.familyhome.api.user.vo.MemberApiVO(
                                                2L, "成员二", "MEMBER"))));
        var conversation = service.create(a, "token", new ConversationCreateDTO(2L));
        var message =
                service.send(
                        a,
                        "token",
                        conversation.id(),
                        new MessageSendDTO(UUID.randomUUID().toString(), "TEXT", "回我一下"));
        // 先把新消息帧收掉，下面才能确定收到的是回执
        assertThat(poll(aQueue)).contains("\"type\":\"message\"");
        assertThat(poll(bQueue)).contains("\"type\":\"message\"");

        service.read(b, conversation.id(), new MessageReadDTO(message.id()));
        // 重复上报同一个水位不该再推一次：否则每开一次会话，全家都收到一条无变化的回执
        service.read(b, conversation.id(), new MessageReadDTO(message.id()));

        assertThat(poll(aQueue))
                .contains("\"type\":\"read\"")
                .contains("\"userId\":2")
                .contains("\"lastMessageId\":" + message.id());
        assertThat(poll(bQueue)).contains("\"type\":\"read\"");
        assertThat(bystanderQueue.poll(1, TimeUnit.SECONDS)).isNull();
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
