package com.familyhome.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.familyhome.media.client.AiRecordingClient;
import com.familyhome.media.dto.CallStartDTO;
import com.familyhome.media.dto.RecordingSubmitDTO;
import com.familyhome.media.service.CallRecordingService;
import com.familyhome.media.service.CallService;
import com.familyhome.media.vo.CallRecordingVO;
import com.familyhome.media.vo.CallVO;
import com.familyhome.security.SessionPrincipal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 录音编排链路：分段转写 → 按序拼接 → 生成纪要 → 状态落库。
 *
 * <p>刻意不加 {@code @Transactional}：后台线程用自己的连接读数据，
 * 测试事务不提交的话它永远看不到这条记录，测出来的「失败」是假的。
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:recording-flow-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.cloud.nacos.discovery.enabled=false",
            "family.messaging.enabled=false",
            "family.media.verify-members=false",
            "family.media.ring-timeout-seconds=10",
            "family.auth.jwt-secret=recording-flow-test-secret-32-bytes",
            "family.clients.user-url=http://127.0.0.1:18081",
            "family.clients.file-url=http://127.0.0.1:18087"
        })
class CallRecordingFlowTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("FAMILY_JWT_SECRET", () -> UUID.randomUUID() + UUID.randomUUID().toString());
    }

    @MockBean private AiRecordingClient ai;

    @Autowired private CallService calls;
    @Autowired private CallRecordingService recordings;

    private final SessionPrincipal caller = new SessionPrincipal(21, 111, "MEMBER", "f1", "family");
    private final SessionPrincipal callee = new SessionPrincipal(22, 111, "MEMBER", "f2", "family");

    private CallVO activeCall() {
        CallVO started = calls.start(caller, null, new CallStartDTO("DIRECT", "AUDIO", callee.userId(), null));
        return calls.answer(callee, started.id());
    }

    private CallRecordingVO awaitFinished(String callId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);
        CallRecordingVO current = null;
        while (System.currentTimeMillis() < deadline) {
            current = recordings.ofCall(caller, callId);
            if (current != null && !"UPLOADED".equals(current.status()) && !"TRANSCRIBING".equals(current.status()))
                return current;
            TimeUnit.MILLISECONDS.sleep(150);
        }
        return current;
    }

    /**
     * 建通话 → 提交录音 → 结束通话。
     *
     * <p>必须结束通话：同一个用户不能同时挂两通电话，而本测试不用事务回滚
     * （后台线程要能读到提交的记录），残留的进行中会话会让下一个用例直接 409。
     */
    private String submitRecording(RecordingSubmitDTO body) {
        CallVO call = activeCall();
        recordings.submit(caller, "Bearer test", call.id(), body);
        calls.end(caller, call.id());
        return call.id();
    }

    @Test
    @DisplayName("分段按 seq 拼成完整转写稿，再生成纪要并置为 READY")
    void transcribesSegmentsInOrderThenWritesMinutes() throws Exception {
        when(ai.transcribe(eq("file-1"), any(), anyString())).thenReturn("第一段：周六上午去医院");
        when(ai.transcribe(eq("file-2"), any(), anyString())).thenReturn("第二段：下午接孩子");
        when(ai.minutes(anyString(), anyList(), anyString(), anyInt(), anyString()))
                .thenReturn("## 一句话结论\n周六上午看诊，下午接孩子。");

        String callId = submitRecording(new RecordingSubmitDTO(
                "周末安排",
                true,
                120_000,
                List.of(
                        new RecordingSubmitDTO.Segment("file-1", 60_000),
                        new RecordingSubmitDTO.Segment("file-2", 60_000))));

        CallRecordingVO finished = awaitFinished(callId);
        assertThat(finished).isNotNull();
        assertThat(finished.status()).isEqualTo("READY");
        assertThat(finished.transcript()).isEqualTo("第一段：周六上午去医院\n第二段：下午接孩子");
        assertThat(finished.minutes()).contains("周六上午看诊");
        assertThat(finished.error()).isNull();
    }

    @Test
    @DisplayName("模型说没听到语音时置为 FAILED 并保留可读原因")
    void emptyTranscriptFails() throws Exception {
        when(ai.transcribe(anyString(), any(), anyString())).thenReturn("   ");

        String callId = submitRecording(new RecordingSubmitDTO(
                null, true, 60_000, List.of(new RecordingSubmitDTO.Segment("file-empty", 60_000))));

        CallRecordingVO finished = awaitFinished(callId);
        assertThat(finished).isNotNull();
        assertThat(finished.status()).isEqualTo("FAILED");
        assertThat(finished.error()).contains("没有识别到语音内容");
    }

    @Test
    @DisplayName("转写服务异常时置为 FAILED，错误信息不会吞成 500")
    void aiFailureMarksFailed() throws Exception {
        when(ai.transcribe(anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("AI 服务暂不可用"));

        String callId = submitRecording(new RecordingSubmitDTO(
                null, true, 60_000, List.of(new RecordingSubmitDTO.Segment("file-x", 60_000))));

        CallRecordingVO finished = awaitFinished(callId);
        assertThat(finished).isNotNull();
        assertThat(finished.status()).isEqualTo("FAILED");
        assertThat(finished.error()).contains("AI 服务暂不可用");
    }
}
