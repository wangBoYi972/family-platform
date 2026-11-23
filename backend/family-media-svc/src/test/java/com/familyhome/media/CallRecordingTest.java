package com.familyhome.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familyhome.media.dto.CallStartDTO;
import com.familyhome.media.dto.RecordingSubmitDTO;
import com.familyhome.media.service.CallRecordingService;
import com.familyhome.media.service.CallService;
import com.familyhome.media.vo.CallRecordingVO;
import com.familyhome.media.vo.CallVO;
import com.familyhome.security.SessionPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 通话录音登记与权限。
 *
 * <p>只覆盖「登记」这一段：转写在后台线程里跑，测试事务未提交时它读不到记录，
 * 真去调用还会打到外部模型服务。转写本身由 {@code CallRecordingProcessor} 承担，
 * 它的行为靠 AiRecordingClient 的接口契约约束。
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:recording-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.cloud.nacos.discovery.enabled=false",
            "family.messaging.enabled=false",
            "family.media.verify-members=false",
            "family.media.ring-timeout-seconds=10",
            // 直接给出密钥：ServiceSecurity 要求 32 字节以上，靠环境变量占位在 CI/本地都会漏
            "family.auth.jwt-secret=recording-test-secret-at-least-32-bytes",
            "family.clients.user-url=http://127.0.0.1:18081",
            "family.clients.file-url=http://127.0.0.1:18087"
        })
@Transactional
class CallRecordingTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String secret = UUID.randomUUID() + UUID.randomUUID().toString();
        registry.add("FAMILY_JWT_SECRET", () -> secret);
    }

    @Autowired private CallService calls;
    @Autowired private CallRecordingService recordings;

    private final SessionPrincipal caller = new SessionPrincipal(11, 101, "MEMBER", "s1", "family");
    private final SessionPrincipal callee = new SessionPrincipal(12, 101, "MEMBER", "s2", "family");
    private final SessionPrincipal outsider = new SessionPrincipal(13, 101, "MEMBER", "s3", "family");
    private final SessionPrincipal admin = new SessionPrincipal(10, 101, "ADMIN", "s0", "family");
    private final SessionPrincipal foreign = new SessionPrincipal(91, 902, "MEMBER", "s9", "family");

    private CallVO activeCall() {
        CallVO started = calls.start(caller, null, new CallStartDTO("DIRECT", "AUDIO", callee.userId(), null));
        return calls.answer(callee, started.id());
    }

    private RecordingSubmitDTO submission(boolean consent) {
        return new RecordingSubmitDTO(
                "周末安排",
                consent,
                65_000,
                List.of(
                        new RecordingSubmitDTO.Segment("file-1", 60_000),
                        new RecordingSubmitDTO.Segment("file-2", 5_000)));
    }

    @Test
    @DisplayName("未勾选知情同意不允许生成纪要")
    void rejectsWithoutConsent() {
        CallVO call = activeCall();
        assertThatThrownBy(() -> recordings.submit(caller, null, call.id(), submission(false)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("只被邀请、没接听的人不能提交录音")
    void rejectsOutsider() {
        CallVO call = activeCall();
        assertThatThrownBy(() -> recordings.submit(outsider, null, call.id(), submission(true)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("跨家庭看不到这通电话的录音")
    void rejectsForeignHousehold() {
        CallVO call = activeCall();
        assertThatThrownBy(() -> recordings.submit(foreign, null, call.id(), submission(true)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("提交后返回上传状态与分段数量，参与者能查到，无关成员查不到")
    void submitAndRead() {
        CallVO call = activeCall();
        CallRecordingVO created = recordings.submit(caller, null, call.id(), submission(true));
        assertThat(created.status()).isEqualTo("UPLOADED");
        assertThat(created.segmentCount()).isEqualTo(2);
        assertThat(created.durationMs()).isEqualTo(65_000);
        assertThat(created.recordedBy()).isEqualTo(caller.userId());
        assertThat(created.consentConfirmed()).isTrue();

        assertThat(recordings.ofCall(callee, call.id()).id()).isEqualTo(created.id());
        assertThat(recordings.detail(admin, created.id()).id()).isEqualTo(created.id());
        assertThatThrownBy(() -> recordings.ofCall(outsider, call.id()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("未提交过录音的通话返回 null，而不是 404")
    void ofCallReturnsNullWhenAbsent() {
        CallVO call = activeCall();
        assertThat(recordings.ofCall(caller, call.id())).isNull();
    }

    @Test
    @DisplayName("同一通电话重复提交被拒绝：一份纪要只能来自一份录音")
    void rejectsDuplicate() {
        CallVO call = activeCall();
        recordings.submit(caller, null, call.id(), submission(true));
        assertThatThrownBy(() -> recordings.submit(caller, null, call.id(), submission(true)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("录音状态写进通话快照，房间内所有人都能看到谁在录")
    void recordingStateIsShared() {
        CallVO call = activeCall();
        assertThat(call.recording()).isFalse();

        CallVO on = calls.setRecording(caller, call.id(), true);
        assertThat(on.recording()).isTrue();
        assertThat(on.recordedBy()).isEqualTo(caller.userId());
        assertThat(calls.detail(callee, call.id()).recording()).isTrue();

        assertThatThrownBy(() -> calls.setRecording(outsider, call.id(), true))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        CallVO off = calls.setRecording(caller, call.id(), false);
        assertThat(off.recording()).isFalse();
        assertThat(off.recordedBy()).isNull();
    }

    @Test
    @DisplayName("同时只能有一个人在录音，第二个发起者被拒绝")
    void onlyOneRecorderAtATime() {
        CallVO call = activeCall();
        calls.setRecording(caller, call.id(), true);
        assertThatThrownBy(() -> calls.setRecording(callee, call.id(), true))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("只有开启录音的人（或管理员）能停止录音")
    void onlyOwnerCanStop() {
        CallVO call = activeCall();
        calls.setRecording(caller, call.id(), true);
        assertThatThrownBy(() -> calls.setRecording(callee, call.id(), false))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(calls.setRecording(admin, call.id(), false).recording()).isFalse();
    }
}
