package com.familyhome.media.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.common.api.PageQuery;
import com.familyhome.media.dto.RecordingSubmitDTO;
import com.familyhome.media.mapper.CallParticipantMapper;
import com.familyhome.media.mapper.CallRecordingMapper;
import com.familyhome.media.mapper.CallRecordingSegmentMapper;
import com.familyhome.media.mapper.CallSessionMapper;
import com.familyhome.media.model.CallParticipant;
import com.familyhome.media.model.CallRecording;
import com.familyhome.media.model.CallRecordingSegment;
import com.familyhome.media.model.CallSession;
import com.familyhome.media.model.Calls;
import com.familyhome.media.service.CallRecordingProcessor;
import com.familyhome.media.service.CallRecordingService;
import com.familyhome.media.vo.CallRecordingVO;
import com.familyhome.security.SessionPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class CallRecordingServiceImpl implements CallRecordingService {

    private static final Logger log = LoggerFactory.getLogger(CallRecordingServiceImpl.class);
    private static final DateTimeFormatter TITLE_TIME = DateTimeFormatter.ofPattern("MM月dd日 HH:mm");

    @Autowired private CallSessionMapper sessions;
    @Autowired private CallParticipantMapper participants;
    @Autowired private CallRecordingMapper recordings;
    @Autowired private CallRecordingSegmentMapper segments;
    @Autowired private CallRecordingProcessor processor;

    @Override
    @Transactional
    public CallRecordingVO submit(
            SessionPrincipal principal, String bearer, String callId, RecordingSubmitDTO input) {
        CallSession call = requireCall(principal.householdId(), callId);
        CallParticipant me = participantOf(callId, principal.userId());
        // 被邀请但没接听的人手里不可能有录音，允许他提交等于给伪造留口子
        if (me == null || me.getJoinedAt() == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有参加过这通电话的成员可以提交录音");
        if (!Boolean.TRUE.equals(input.consent()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先确认所有参与者都知情同意");

        CallRecording existing = recordings.selectOne(
                new LambdaQueryWrapper<CallRecording>().eq(CallRecording::getCallId, callId));
        if (existing != null)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    Calls.RECORDING_FAILED.equals(existing.getStatus())
                            ? "这通电话已有一份失败的录音，请在纪要页重试"
                            : "这通电话的纪要已在生成中或已生成");

        LocalDateTime now = LocalDateTime.now();
        CallRecording recording = new CallRecording();
        recording.setId(UUID.randomUUID().toString());
        recording.setCallId(callId);
        recording.setHouseholdId(principal.householdId());
        recording.setTitle(titleOf(call, input.title()));
        recording.setStatus(Calls.RECORDING_UPLOADED);
        recording.setDurationMs(normalizeDuration(input.durationMs()));
        recording.setRecordedBy(principal.userId());
        recording.setConsentConfirmed(true);
        recording.setCreatedAt(now);
        recording.setUpdatedAt(now);
        try {
            recordings.insert(recording);
        } catch (DuplicateKeyException ex) {
            // 两端同时挂断各提交一次时，唯一索引兜住第二次
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这通电话的录音已提交过");
        }

        int seq = 1;
        for (RecordingSubmitDTO.Segment segment : input.segments()) {
            CallRecordingSegment row = new CallRecordingSegment();
            row.setRecordingId(recording.getId());
            row.setSeq(seq++);
            row.setFileId(segment.fileId());
            row.setDurationMs(normalizeDuration(segment.durationMs()));
            segments.insert(row);
        }

        // 事务提交后再排队：异步线程可能比事务快，先跑就会查不到刚插入的记录
        triggerAfterCommit(recording.getId(), bearer);
        log.info("通话录音已登记 callId={} segments={} durationMs={}",
                callId, input.segments().size(), recording.getDurationMs());
        return toVO(recording, input.segments().size(), false);
    }

    @Override
    public CallRecordingVO ofCall(SessionPrincipal principal, String callId) {
        CallRecording recording = recordings.selectOne(
                new LambdaQueryWrapper<CallRecording>()
                        .eq(CallRecording::getCallId, callId)
                        .eq(CallRecording::getHouseholdId, principal.householdId()));
        if (recording == null) return null;
        requireReadable(recording, principal);
        return toVO(recording, segmentCount(recording.getId()), true);
    }

    @Override
    public CallRecordingVO detail(SessionPrincipal principal, String id) {
        CallRecording recording = requireRecording(principal.householdId(), id);
        requireReadable(recording, principal);
        return toVO(recording, segmentCount(recording.getId()), true);
    }

    @Override
    public List<CallRecordingVO> list(SessionPrincipal principal, PageQuery page) {
        List<CallRecording> rows = recordings.selectList(
                new LambdaQueryWrapper<CallRecording>()
                        .eq(CallRecording::getHouseholdId, principal.householdId())
                        .orderByDesc(CallRecording::getCreatedAt)
                        .last(page.limitClause()));
        return rows.stream().map(row -> toVO(row, null, false)).toList();
    }

    @Override
    @Transactional
    public CallRecordingVO retry(SessionPrincipal principal, String bearer, String id) {
        CallRecording recording = requireRecording(principal.householdId(), id);
        requireReadable(recording, principal);
        if (!Calls.RECORDING_FAILED.equals(recording.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有失败的纪要可以重试");

        recording.setStatus(Calls.RECORDING_UPLOADED);
        recording.setError(null);
        recording.setUpdatedAt(LocalDateTime.now());
        recordings.updateById(recording);
        triggerAfterCommit(recording.getId(), bearer);
        return toVO(recording, segmentCount(id), false);
    }

    private void triggerAfterCommit(String recordingId, String bearer) {
        Runnable task = () -> {
            try {
                processor.process(recordingId, bearer);
            } catch (RuntimeException ex) {
                // 线程池已满时 CallerRuns 会在这里同步执行失败，不能让提交接口变成 500
                log.warn("录音任务排队失败 recordingId={}", recordingId, ex);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }

    private CallSession requireCall(long householdId, String callId) {
        CallSession call = sessions.selectById(callId);
        if (call == null || !Objects.equals(call.getHouseholdId(), householdId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通话不存在");
        return call;
    }

    private CallRecording requireRecording(long householdId, String id) {
        CallRecording recording = recordings.selectById(id);
        if (recording == null || !Objects.equals(recording.getHouseholdId(), householdId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "纪要不存在");
        return recording;
    }

    /**
     * 录音是参与者之间的对话，默认只有参与者能看；管理员保留查看权，
     * 否则家庭里出事时没有任何人能把记录调出来。
     */
    private void requireReadable(CallRecording recording, SessionPrincipal principal) {
        if (principal.admin()) return;
        CallParticipant me = participantOf(recording.getCallId(), principal.userId());
        if (me == null || me.getJoinedAt() == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有通话参与者可以查看这通电话的纪要");
    }

    private CallParticipant participantOf(String callId, long userId) {
        return participants.selectOne(
                new LambdaQueryWrapper<CallParticipant>()
                        .eq(CallParticipant::getCallId, callId)
                        .eq(CallParticipant::getUserId, userId));
    }

    private int segmentCount(String recordingId) {
        return segments.selectCount(
                new LambdaQueryWrapper<CallRecordingSegment>()
                        .eq(CallRecordingSegment::getRecordingId, recordingId)).intValue();
    }

    private static String titleOf(CallSession call, String given) {
        if (given != null && !given.isBlank()) return given.strip();
        LocalDateTime at = call.getCreatedAt() == null ? LocalDateTime.now() : call.getCreatedAt();
        return "通话纪要 · " + at.format(TITLE_TIME);
    }

    private static Integer normalizeDuration(Integer value) {
        if (value == null || value < 0) return null;
        return value;
    }

    private static CallRecordingVO toVO(CallRecording row, Integer segmentCount, boolean withBody) {
        return new CallRecordingVO(
                row.getId(),
                row.getCallId(),
                row.getTitle(),
                row.getStatus(),
                row.getDurationMs(),
                row.getRecordedBy(),
                Boolean.TRUE.equals(row.getConsentConfirmed()),
                segmentCount,
                withBody ? row.getTranscript() : null,
                withBody ? row.getMinutes() : null,
                row.getError(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
