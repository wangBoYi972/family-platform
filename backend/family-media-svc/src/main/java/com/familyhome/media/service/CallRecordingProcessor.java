package com.familyhome.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.api.user.feign.UserFeignClient;
import com.familyhome.api.user.vo.MemberApiVO;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.media.client.AiRecordingClient;
import com.familyhome.media.mapper.CallParticipantMapper;
import com.familyhome.media.mapper.CallRecordingMapper;
import com.familyhome.media.mapper.CallRecordingSegmentMapper;
import com.familyhome.media.model.CallParticipant;
import com.familyhome.media.model.CallRecording;
import com.familyhome.media.model.CallRecordingSegment;
import com.familyhome.media.model.Calls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 录音转写与纪要生成的后台任务。
 *
 * <p>单独成 Bean 而不是写在 {@code CallRecordingServiceImpl} 里：{@code @Async}
 * 走代理，同类内部调用不会异步，反而会在请求线程里同步跑完 40 分钟的音频转写。
 *
 * <p>状态推进是「先记 TRANSCRIBING 再干活」：进程重启时这条记录会停在 TRANSCRIBING，
 * 用户能从列表看出「卡住了」，并且可以点重试，而不是永远停在「排队中」。
 */
@Component
public class CallRecordingProcessor {

    private static final Logger log = LoggerFactory.getLogger(CallRecordingProcessor.class);

    @Autowired private CallRecordingMapper recordings;
    @Autowired private CallRecordingSegmentMapper segments;
    @Autowired private CallParticipantMapper participants;
    @Autowired private AiRecordingClient ai;
    @Autowired private ObjectProvider<UserFeignClient> userClient;

    @Async("recordingExecutor")
    public void process(String recordingId, String bearer) {
        CallRecording recording = recordings.selectById(recordingId);
        if (recording == null) return;
        // 只认「已提交」这一种入口状态：重投或重复排队不应再跑一遍转写（会重复计费）
        if (!Calls.RECORDING_UPLOADED.equals(recording.getStatus())) return;
        markStatus(recordingId, Calls.RECORDING_TRANSCRIBING, null, null);

        try {
            String transcript = transcribeAll(recordingId, bearer);
            if (transcript.isEmpty()) {
                markStatus(recordingId, Calls.RECORDING_FAILED, null,
                        "没有识别到语音内容，可能全程静音或麦克风被关闭");
                return;
            }
            markStatus(recordingId, Calls.RECORDING_TRANSCRIBING, transcript, null);

            String minutes = ai.minutes(
                    recording.getTitle(),
                    speakers(bearer, recording.getCallId()),
                    transcript,
                    recording.getDurationMs(),
                    bearer);
            if (minutes == null || minutes.isBlank()) {
                markStatus(recordingId, Calls.RECORDING_FAILED, transcript, "模型没有返回纪要内容");
                return;
            }
            markStatus(recordingId, Calls.RECORDING_READY, transcript, null, minutes);
            log.info("通话纪要生成完成 callId={} chars={}", recording.getCallId(), transcript.length());
        } catch (RuntimeException ex) {
            log.warn("通话纪要生成失败 callId={}", recording.getCallId(), ex);
            markStatus(recordingId, Calls.RECORDING_FAILED, null, shorten(ex.getMessage()));
        }
    }

    /** 逐段转写并按 seq 拼接。单段失败即整体失败：缺一段的纪要比没有纪要更容易误导人。 */
    private String transcribeAll(String recordingId, String bearer) {
        List<CallRecordingSegment> list = segments.selectList(
                new LambdaQueryWrapper<CallRecordingSegment>()
                        .eq(CallRecordingSegment::getRecordingId, recordingId)
                        .orderByAsc(CallRecordingSegment::getSeq));
        StringBuilder transcript = new StringBuilder();
        for (CallRecordingSegment segment : list) {
            String text = ai.transcribe(segment.getFileId(), null, bearer);
            segment.setTranscript(text);
            segments.updateById(segment);
            if (text == null || text.isBlank()) continue;
            if (transcript.length() > 0) transcript.append('\n');
            transcript.append(text.strip());
        }
        return transcript.toString().strip();
    }

    /**
     * 参与人称呼。取不到成员名单时统一叫「家人」，
     * 让模型用泛称而不是自己编号出「说话人A」，避免纪要里出现虚构角色。
     */
    private List<String> speakers(String bearer, String callId) {
        Map<Long, String> names = memberNames(bearer);
        List<CallParticipant> people = participants.selectList(
                new LambdaQueryWrapper<CallParticipant>()
                        .eq(CallParticipant::getCallId, callId)
                        .orderByAsc(CallParticipant::getId));
        return people.stream()
                .map(pt -> names.get(pt.getUserId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    private Map<Long, String> memberNames(String bearer) {
        try {
            UserFeignClient client = userClient.getIfAvailable();
            if (client == null) return Map.of();
            ApiResponse<List<MemberApiVO>> response = client.members(bearer);
            if (response == null || response.code() != 0 || response.data() == null) return Map.of();
            List<MemberApiVO> members = new ArrayList<>(response.data());
            return members.stream()
                    .filter(item -> item.id() != null)
                    .collect(Collectors.toMap(
                            MemberApiVO::id,
                            item -> item.displayName() == null || item.displayName().isBlank()
                                    ? "家人" : item.displayName(),
                            (left, right) -> left));
        } catch (RuntimeException ex) {
            // 成员名只是让纪要读起来顺口，取不到不该让整个任务失败
            log.debug("获取成员称呼失败，纪要将使用泛称", ex);
            return Map.of();
        }
    }

    private void markStatus(String recordingId, String status, String transcript, String error) {
        markStatus(recordingId, status, transcript, error, null);
    }

    private void markStatus(String recordingId, String status, String transcript, String error, String minutes) {
        LambdaUpdateWrapper<CallRecording> update = new LambdaUpdateWrapper<CallRecording>()
                .eq(CallRecording::getId, recordingId)
                .set(CallRecording::getStatus, status)
                .set(CallRecording::getUpdatedAt, LocalDateTime.now());
        if (transcript != null) update.set(CallRecording::getTranscript, transcript);
        if (minutes != null) update.set(CallRecording::getMinutes, minutes);
        update.set(CallRecording::getError, error);
        recordings.update(null, update);
    }

    private static String shorten(String message) {
        if (message == null || message.isBlank()) return "转写失败，请稍后重试";
        return message.length() <= 180 ? message : message.substring(0, 180);
    }
}
