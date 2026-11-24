package com.familyhome.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familyhome.media.config.LiveKitProperties;
import com.familyhome.media.dto.CallStartDTO;
import com.familyhome.media.service.CallService;
import com.familyhome.media.service.LiveKitTokenService;
import com.familyhome.media.vo.LiveKitTokenVO;
import com.familyhome.security.SessionPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** LiveKit 入房凭证的签发与安全边界。凭证一旦签发即可收发媒体，鉴权测试必须覆盖全部拒绝路径。 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:livekit-token-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.cloud.nacos.discovery.enabled=false",
            "family.messaging.enabled=false",
            "family.media.verify-members=false",
            "family.auth.jwt-secret=call-integration-test-secret-32-bytes",
            "family.livekit.enabled=true",
            "family.livekit.api-key=test-livekit-key",
            "family.livekit.api-secret=livekit-test-api-secret-32-bytes",
            "family.livekit.public-url=wss://livekit.test.local",
            "family.livekit.room-prefix=family-test-",
            "family.livekit.token-ttl-seconds=600"
        })
@Transactional
class LiveKitTokenServiceTest {
    private static final String API_KEY = "test-livekit-key";
    private static final String API_SECRET = "livekit-test-api-secret-32-bytes";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("FAMILY_JWT_SECRET", () -> UUID.randomUUID() + UUID.randomUUID().toString());
    }

    @Autowired private LiveKitTokenService tokens;
    @Autowired private CallService calls;
    @Autowired private LiveKitProperties properties;

    private final SessionPrincipal caller = new SessionPrincipal(11, 101, "MEMBER", "s1", "family");
    private final SessionPrincipal callee = new SessionPrincipal(12, 101, "MEMBER", "s2", "family");
    // 与通话同一家庭，但不是这通电话的参与者 —— 最容易漏掉的内部越权场景
    private final SessionPrincipal outsider = new SessionPrincipal(13, 101, "MEMBER", "s3", "family");
    private final SessionPrincipal foreign = new SessionPrincipal(91, 902, "MEMBER", "s9", "family");
    // detail() 对管理员放行了非参与者查看，但媒体房不能因此放行
    private final SessionPrincipal admin = new SessionPrincipal(10, 101, "ADMIN", "s0", "family");

    private String directCall(String kind) {
        return calls.start(caller, null, new CallStartDTO("DIRECT", kind, callee.userId(), null)).id();
    }

    private void assertStatus(Throwable error, HttpStatus expected) {
        assertThat(error).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(expected);
    }

    @Test
    @DisplayName("发起者（JOINED）可签发凭证，JWT 的 grant / 房间 / 有效期均正确")
    void joinedParticipantGetsValidToken() {
        String callId = directCall("VIDEO");
        LiveKitTokenVO vo = tokens.issue(caller, callId);

        assertThat(vo.url()).isEqualTo("wss://livekit.test.local");
        assertThat(vo.roomName()).isEqualTo("family-test-" + callId);
        assertThat(vo.ttlSeconds()).isEqualTo(600);

        Claims claims =
                Jwts.parser()
                        .verifyWith(Keys.hmacShaKeyFor(API_SECRET.getBytes(StandardCharsets.UTF_8)))
                        .build()
                        .parseSignedClaims(vo.token())
                        .getPayload();
        assertThat(claims.getIssuer()).isEqualTo(API_KEY);
        // identity 必须来自服务端会话，不能是客户端参数
        assertThat(claims.getSubject()).isEqualTo("11");

        @SuppressWarnings("unchecked")
        Map<String, Object> video = (Map<String, Object>) claims.get("video");
        assertThat(video)
                .containsEntry("roomJoin", true)
                .containsEntry("room", "family-test-" + callId)
                .containsEntry("canPublish", true)
                .containsEntry("canSubscribe", true)
                .containsEntry("canPublishData", true)
                // 客户端不得自建任意房间
                .doesNotContainKey("roomCreate");

        long ttlMs = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertThat(ttlMs).isEqualTo(600_000L);
        assertThat(claims.getExpiration()).isAfter(new Date());
        assertThat(claims.getNotBefore()).isBefore(new Date());
    }

    @Test
    @DisplayName("被叫（INVITED）也能提前取凭证，接通瞬间可直接入房")
    void invitedParticipantGetsToken() {
        String callId = directCall("VIDEO");
        LiveKitTokenVO vo = tokens.issue(callee, callId);
        assertThat(vo.roomName()).isEqualTo("family-test-" + callId);
    }

    @Test
    @DisplayName("同一家庭但不在通话中的成员取凭证返回 403")
    void nonParticipantForbidden() {
        String callId = directCall("VIDEO");
        assertStatus(
                org.assertj.core.api.Assertions.catchThrowable(() -> tokens.issue(outsider, callId)),
                HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("管理员即便能看详情，也不能旁听家庭媒体房")
    void adminNonParticipantForbidden() {
        String callId = directCall("VIDEO");
        assertStatus(
                org.assertj.core.api.Assertions.catchThrowable(() -> tokens.issue(admin, callId)),
                HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("跨家庭的 callId 返回 404，不泄漏其它家庭通话是否存在")
    void foreignHouseholdNotFound() {
        String callId = directCall("VIDEO");
        assertStatus(
                org.assertj.core.api.Assertions.catchThrowable(() -> tokens.issue(foreign, callId)),
                HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("拒接后参与者状态变为 REJECTED，不能再取凭证")
    void rejectedParticipantForbidden() {
        String callId = directCall("VIDEO");
        calls.reject(callee, callId);
        assertStatus(
                org.assertj.core.api.Assertions.catchThrowable(() -> tokens.issue(callee, callId)),
                HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("语音通话只允许发布麦克风，不能误开摄像头")
    void audioCallRestrictsPublishSources() {
        String callId = directCall("AUDIO");
        LiveKitTokenVO vo = tokens.issue(caller, callId);

        Claims claims =
                Jwts.parser()
                        .verifyWith(Keys.hmacShaKeyFor(API_SECRET.getBytes(StandardCharsets.UTF_8)))
                        .build()
                        .parseSignedClaims(vo.token())
                        .getPayload();
        @SuppressWarnings("unchecked")
        Map<String, Object> video = (Map<String, Object>) claims.get("video");
        assertThat(video.get("canPublishSources")).isEqualTo(java.util.List.of("microphone"));
    }

    @Test
    @DisplayName("feature flag 关闭时拒绝签发，行为完全退回 mesh")
    void disabledFlagRejects() {
        String callId = directCall("VIDEO");
        ReflectionTestUtils.setField(properties, "enabled", false);
        try {
            assertStatus(
                    org.assertj.core.api.Assertions.catchThrowable(
                            () -> tokens.issue(caller, callId)),
                    HttpStatus.CONFLICT);
        } finally {
            ReflectionTestUtils.setField(properties, "enabled", true);
        }
    }

    @Test
    @DisplayName("凭据缺失或密钥过短时快速失败，而不是签出连不上的 token")
    void misconfigurationFailsFast() {
        String callId = directCall("VIDEO");
        String original = properties.getApiSecret();
        ReflectionTestUtils.setField(properties, "apiSecret", "too-short");
        try {
            assertThatThrownBy(() -> tokens.issue(caller, callId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32 字节");
        } finally {
            ReflectionTestUtils.setField(properties, "apiSecret", original);
        }
    }
}
