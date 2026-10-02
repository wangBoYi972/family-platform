package com.familyhome.im;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.familyhome.api.file.feign.FileFeignClient;
import com.familyhome.api.user.feign.UserFeignClient;
import com.familyhome.api.user.vo.MemberApiVO;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.im.dto.*;
import com.familyhome.im.service.ImService;
import com.familyhome.security.SessionPrincipal;
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
}
