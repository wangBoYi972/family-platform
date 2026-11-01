package com.familyhome.im;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.familyhome.api.file.feign.FileFeignClient;
import com.familyhome.api.user.feign.UserFeignClient;
import com.familyhome.api.user.vo.MemberApiVO;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.im.dto.*;
import com.familyhome.im.mapper.ChatMessageMapper;
import com.familyhome.im.service.ImService;
import com.familyhome.security.SessionPrincipal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:im-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.cloud.nacos.discovery.enabled=false"
        })
@ActiveProfiles("local")
class ImIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        String secret = UUID.randomUUID().toString() + UUID.randomUUID();
        r.add("FAMILY_JWT_SECRET", () -> secret);
    }

    @Autowired ImService service;
    @Autowired ChatMessageMapper messagesMapper;
    @MockBean UserFeignClient users;
    @MockBean FileFeignClient files;

    @Test
    @DisplayName("私聊成员隔离、发送幂等和已读游标不回退")
    void conversationIsolationAndIdempotency() {
        var a = new SessionPrincipal(1, 10, "MEMBER", "test", "family");
        var b = new SessionPrincipal(2, 10, "MEMBER", "test", "family");
        var c = new SessionPrincipal(3, 10, "ADMIN", "test", "family");
        when(users.members("token"))
                .thenReturn(ApiResponse.success(List.of(new MemberApiVO(2L, "成员二", "MEMBER"))));
        var conversation = service.create(a, "token", new ConversationCreateDTO(2L));
        assertThatThrownBy(() -> service.detail(c, conversation.id()))
                .isInstanceOf(ResponseStatusException.class);
        var input = new MessageSendDTO(UUID.randomUUID().toString(), "TEXT", "周末一起吃饭");
        var message = service.send(a, "token", conversation.id(), input);
        var repeated = service.send(a, "token", conversation.id(), input);
        assertThat(repeated.id()).isEqualTo(message.id());
        assertThat(service.messages(b, conversation.id(), 0)).hasSize(1);
        assertThatThrownBy(
                        () ->
                                service.send(
                                        a,
                                        "token",
                                        conversation.id(),
                                        new MessageSendDTO(input.clientId(), "TEXT", "不同内容")))
                .isInstanceOf(ResponseStatusException.class);
        var next =
                service.send(
                        a,
                        "token",
                        conversation.id(),
                        new MessageSendDTO(UUID.randomUUID().toString(), "TEXT", "第二条"));
        service.read(b, conversation.id(), new MessageReadDTO(next.id()));
        service.read(b, conversation.id(), new MessageReadDTO(message.id()));
        assertThat(service.reads(b, conversation.id()).getFirst().lastMessageId()).isEqualTo(next.id());
        assertThat(service.detail(b, conversation.id()).unread()).isZero();
    }

    @Test
    @DisplayName("会话变长后默认给最新消息，「加载更早」按游标补历史")
    void messagesStartFromLatestAndSupportEarlier() {
        var a = new SessionPrincipal(11, 20, "MEMBER", "test", "family");
        var b = new SessionPrincipal(12, 20, "MEMBER", "test", "family");
        when(users.members("token"))
                .thenReturn(ApiResponse.success(List.of(new MemberApiVO(12L, "成员二", "MEMBER"))));
        var conversation = service.create(a, "token", new ConversationCreateDTO(12L));
        for (int i = 1; i <= 130; i++) {
            service.send(
                    a,
                    "token",
                    conversation.id(),
                    new MessageSendDTO(UUID.randomUUID().toString(), "TEXT", "消息" + i));
        }

        // 首次进入：必须是最近的 100 条，而不是最早的 100 条（早先正序 + LIMIT 100 的写法，
        // 会让第 101 条之后的新消息永远进不了界面）
        var latest = service.messages(b, conversation.id(), 0, 0, 0);
        assertThat(latest).hasSize(100);
        assertThat(latest.getFirst().body()).isEqualTo("消息31");
        assertThat(latest.getLast().body()).isEqualTo("消息130");

        // 加载更早：拿当前最老一条的 id 当游标，补回剩下的 30 条且仍是正序
        var earlier = service.messages(b, conversation.id(), 0, latest.getFirst().id(), 0);
        assertThat(earlier).hasSize(30);
        assertThat(earlier.getFirst().body()).isEqualTo("消息1");
        assertThat(earlier.getLast().body()).isEqualTo("消息30");

        // 增量轮询语义不变：游标在最新一条之后就没有新消息
        assertThat(service.messages(b, conversation.id(), latest.getLast().id(), 0, 0)).isEmpty();
        // 旧签名保持可用（after 语义）
        assertThat(service.messages(b, conversation.id(), earlier.getLast().id())).hasSize(130 - 30);
    }

    @Test
    @DisplayName("撤回：只能撤自己的、窗口外不能撤、撤回后正文清空但消息保留")
    void recallOwnMessageOnlyWithinWindow() {
        var a = new SessionPrincipal(21, 30, "MEMBER", "test", "family");
        var b = new SessionPrincipal(22, 30, "MEMBER", "test", "family");
        when(users.members("token"))
                .thenReturn(ApiResponse.success(List.of(new MemberApiVO(22L, "成员22", "MEMBER"))));
        var conversation = service.create(a, "token", new ConversationCreateDTO(22L));
        var mine =
                service.send(
                        a,
                        "token",
                        conversation.id(),
                        new MessageSendDTO(UUID.randomUUID().toString(), "TEXT", "发错了"));
        var others =
                service.send(
                        b,
                        "token",
                        conversation.id(),
                        new MessageSendDTO(UUID.randomUUID().toString(), "TEXT", "别人的"));

        // 别人的消息不能撤（哪怕是同会话的家人）
        assertThatThrownBy(() -> service.recall(a, conversation.id(), others.id()))
                .isInstanceOf(ResponseStatusException.class);

        // 撤回成功且幂等：正文清空、消息保留占位
        service.recall(a, conversation.id(), mine.id());
        service.recall(a, conversation.id(), mine.id());
        var list = service.messages(b, conversation.id(), 0);
        var recalled =
                list.stream().filter(item -> item.id().equals(mine.id())).findFirst().orElseThrow();
        assertThat(recalled.deleted()).isTrue();
        assertThat(recalled.body()).isEmpty();
        // 对方看到的未撤回消息不受影响
        var kept =
                list.stream().filter(item -> item.id().equals(others.id())).findFirst().orElseThrow();
        assertThat(kept.deleted()).isFalse();

        // 窗口外不能撤：把 createdAt 拨回三分钟前再试
        var old =
                service.send(
                        a,
                        "token",
                        conversation.id(),
                        new MessageSendDTO(UUID.randomUUID().toString(), "TEXT", "很久以前的"));
        var entity = messagesMapper.selectById(old.id());
        entity.setCreatedAt(LocalDateTime.now().minusMinutes(3));
        messagesMapper.updateById(entity);
        assertThatThrownBy(() -> service.recall(a, conversation.id(), old.id()))
                .isInstanceOf(ResponseStatusException.class);
    }
}
