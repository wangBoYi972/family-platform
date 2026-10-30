package com.familyhome.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.media.dto.CallStartDTO;
import com.familyhome.media.mapper.CallSessionMapper;
import com.familyhome.media.model.CallSession;
import com.familyhome.media.service.CallService;
import com.familyhome.media.vo.CallParticipantVO;
import com.familyhome.media.vo.CallVO;
import com.familyhome.security.SessionPrincipal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:call-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.cloud.nacos.discovery.enabled=false",
            "family.messaging.enabled=false",
            "family.media.verify-members=false",
            "family.media.ring-timeout-seconds=10",
            "family.media.turn.secret=test-turn-secret",
            "family.media.turn.urls=turn:turn.example.com:3478?transport=udp",
            "family.media.turn.ttl-seconds=600"
        })
@Transactional
class CallIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String secret = UUID.randomUUID() + UUID.randomUUID().toString();
        registry.add("FAMILY_JWT_SECRET", () -> secret);
    }

    @Autowired private CallService calls;
    @Autowired private CallSessionMapper sessions;

    private final SessionPrincipal caller = new SessionPrincipal(11, 101, "MEMBER", "s1", "family");
    private final SessionPrincipal callee = new SessionPrincipal(12, 101, "MEMBER", "s2", "family");
    private final SessionPrincipal other = new SessionPrincipal(13, 101, "MEMBER", "s3", "family");
    private final SessionPrincipal foreign = new SessionPrincipal(91, 902, "MEMBER", "s9", "family");
    private final SessionPrincipal admin = new SessionPrincipal(10, 101, "ADMIN", "s0", "family");

    private CallVO startDirect() {
        return calls.start(caller, null, new CallStartDTO("DIRECT", "VIDEO", callee.userId(), null));
    }

    @Test
    @DisplayName("一对一：发起 → 接听 → 挂断，状态与参与者正确流转")
    void directCallLifecycle() {
        CallVO call = startDirect();
        assertThat(call.status()).isEqualTo("RINGING");
        assertThat(call.mode()).isEqualTo("DIRECT");
        assertThat(call.participants())
                .extracting(CallParticipantVO::state)
                .containsExactlyInAnyOrder("JOINED", "INVITED");

        CallVO answered = calls.answer(callee, call.id());
        assertThat(answered.status()).isEqualTo("ACTIVE");
        assertThat(answered.answeredAt()).isNotNull();
        assertThat(calls.active(callee)).isNotNull().extracting(CallVO::id).isEqualTo(call.id());

        calls.end(caller, call.id());

        CallVO ended = calls.detail(callee, call.id());
        assertThat(ended.status()).isEqualTo("ENDED");
        assertThat(ended.endReason()).isEqualTo("HANGUP");
        assertThat(calls.active(callee)).isNull();
    }

    @Test
    @DisplayName("拒接后一对一通话直接结束，理由为 REJECTED")
    void rejectEndsDirectCall() {
        CallVO call = startDirect();

        calls.reject(callee, call.id());

        CallVO ended = calls.detail(caller, call.id());
        assertThat(ended.status()).isEqualTo("ENDED");
        assertThat(ended.endReason()).isEqualTo("REJECTED");
        assertThat(ended.participants())
                .filteredOn(item -> item.userId().equals(callee.userId()))
                .extracting(CallParticipantVO::state)
                .containsExactly("REJECTED");
    }

    @Test
    @DisplayName("超时未接听会被清理为 MISSED，且发起者可以重新发起")
    void noAnswerTimeoutMarksMissed() {
        CallVO call = startDirect();
        sessions.update(
                null,
                new LambdaUpdateWrapper<CallSession>()
                        .eq(CallSession::getId, call.id())
                        .set(CallSession::getCreatedAt, LocalDateTime.now().minusSeconds(120)));

        assertThat(calls.sweep()).isGreaterThanOrEqualTo(1);

        CallVO ended = calls.detail(caller, call.id());
        assertThat(ended.status()).isEqualTo("ENDED");
        assertThat(ended.endReason()).isEqualTo("NO_ANSWER");
        assertThat(ended.participants())
                .filteredOn(item -> item.userId().equals(callee.userId()))
                .extracting(CallParticipantVO::state)
                .containsExactly("MISSED");

        assertThat(startDirect().status()).isEqualTo("RINGING");
    }

    @Test
    @DisplayName("家庭隔离：外家庭无法查看；发起者通话中不能重复发起；被叫忙线直接拒绝")
    void enforcesHouseholdIsolationAndBusyState() {
        CallVO call = startDirect();

        assertThatThrownBy(() -> calls.detail(foreign, call.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("通话不存在");
        assertThatThrownBy(() -> calls.answer(foreign, call.id()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> calls.start(caller, null, new CallStartDTO("DIRECT", "AUDIO", other.userId(), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("正在通话中");

        calls.answer(callee, call.id());
        assertThatThrownBy(() -> calls.start(other, null, new CallStartDTO("DIRECT", "AUDIO", callee.userId(), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("对方正在通话中");
    }

    @Test
    @DisplayName("多人通话：中途加入、成员离开后房间保留、发起者挂断整通结束")
    void groupCallKeepsRoomUntilCallerLeaves() {
        CallVO group =
                calls.start(
                        caller,
                        null,
                        new CallStartDTO("GROUP", "AUDIO", null, List.of(callee.userId(), other.userId())));
        assertThat(group.mode()).isEqualTo("GROUP");
        assertThat(group.status()).isEqualTo("RINGING");
        assertThat(group.participants()).hasSize(3);

        calls.answer(callee, group.id());
        calls.answer(other, group.id());
        assertThat(calls.detail(caller, group.id()).status()).isEqualTo("ACTIVE");

        calls.leave(other, group.id());
        assertThat(calls.detail(caller, group.id()).status()).isEqualTo("ACTIVE");

        calls.leave(caller, group.id());
        assertThat(calls.detail(callee, group.id()).status()).isEqualTo("ENDED");
    }

    @Test
    @DisplayName("管理员可以结束非本人发起的通话")
    void adminCanEndAnyCall() {
        CallVO call = startDirect();
        calls.answer(callee, call.id());

        calls.end(admin, call.id());

        assertThat(calls.detail(caller, call.id()).endReason()).isEqualTo("HANGUP");
    }

    @Test
    @DisplayName("TURN 凭据带过期时间且可被服务端复算验证")
    void turnCredentialIsTimeLimited() {
        var servers = calls.iceServers(caller);
        assertThat(servers).isNotEmpty();
        var turn = servers.stream().filter(item -> item.username() != null).findFirst().orElseThrow();
        assertThat(turn.username()).matches("\\d+:" + caller.userId());
        assertThat(turn.credential()).isNotBlank();
        long expiry = Long.parseLong(turn.username().substring(0, turn.username().indexOf(':')));
        assertThat(expiry).isGreaterThan(System.currentTimeMillis() / 1000);
        assertThat(turn.credential())
                .isEqualTo(
                        com.familyhome.media.service.IceServerService.credential(
                                "test-turn-secret", turn.username()));
    }
}
