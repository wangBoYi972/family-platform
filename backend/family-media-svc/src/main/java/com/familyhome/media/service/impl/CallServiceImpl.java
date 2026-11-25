package com.familyhome.media.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.common.api.PageQuery;
import com.familyhome.api.user.feign.UserFeignClient;
import com.familyhome.api.user.vo.MemberApiVO;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.media.config.LiveKitProperties;
import com.familyhome.media.config.MediaProperties;
import com.familyhome.media.dto.CallStartDTO;
import com.familyhome.media.mapper.CallParticipantMapper;
import com.familyhome.media.mapper.CallSessionMapper;
import com.familyhome.media.model.CallParticipant;
import com.familyhome.media.model.CallSession;
import com.familyhome.media.model.Calls;
import com.familyhome.media.service.CallService;
import com.familyhome.media.service.CallSignalBus;
import com.familyhome.media.service.IceServerService;
import com.familyhome.media.vo.CallParticipantVO;
import com.familyhome.media.vo.CallVO;
import com.familyhome.media.vo.IceServerVO;
import com.familyhome.security.SessionPrincipal;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CallServiceImpl implements CallService {
    private static final Logger log = LoggerFactory.getLogger(CallServiceImpl.class);
    private static final long MEMBERSHIP_TTL_MS = 30_000L;
    private static final int MAX_MEMBERSHIP_CACHE_SIZE = 10000;

    @Autowired
    private CallSessionMapper sessions;
    @Autowired
    private CallParticipantMapper participants;
    @Autowired
    private CallSignalBus bus;
    @Autowired
    private IceServerService iceServers;
    @Autowired
    private MediaProperties properties;
    @Autowired
    private LiveKitProperties liveKit;
    @Autowired
    private ObjectProvider<UserFeignClient> userClient;

    /**
     * callId:userId -> 过期时间戳。ICE 帧密集，鉴权不能每帧打库。
     */
    private final Map<String, Long> membershipCache = new ConcurrentHashMap<>();

    @Override
    @Transactional
    public CallVO start(SessionPrincipal principal, String bearer, CallStartDTO dto) {
        LinkedHashSet<Long> targets = resolveTargets(dto);
        targets.remove(principal.userId());
        if (targets.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一位家人");
        int max = effectiveMaxParticipants();
        if (targets.size() + 1 > max)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一次通话最多 " + max + " 人");
        if (activeFor(principal.userId(), principal.householdId()) != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "你正在通话中，请先挂断");
        verifyMembers(bearer, targets);

        boolean direct = targets.size() == 1;
        if (direct
            && activeFor(targets.iterator().next(), principal.householdId()) != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "对方正在通话中");

        LocalDateTime now = LocalDateTime.now();
        var call = new CallSession();
        call.setId(UUID.randomUUID().toString());
        call.setHouseholdId(principal.householdId());
        call.setMode(direct ? Calls.MODE_DIRECT : Calls.MODE_GROUP);
        call.setKind(dto.kind());
        call.setStatus(Calls.STATUS_RINGING);
        call.setCallerId(principal.userId());
        call.setCreatedAt(now);
        sessions.insert(call);

        participant(call.getId(), principal.userId(), Calls.STATE_JOINED, now);
        targets.forEach(userId -> participant(call.getId(), userId, Calls.STATE_INVITED, now));

        CallVO snapshot = snapshot(call);
        List<Long> invitees = List.copyOf(targets);
        afterCommit(
            () ->
                bus.broadcast(
                    "call.invited",
                    call.getId(),
                    call.getHouseholdId(),
                    principal.userId(),
                    invitees,
                    Map.of("call", snapshot)));
        return snapshot;
    }

    @Override
    @Transactional
    public CallVO answer(SessionPrincipal principal, String callId) {
        CallSession call = requireCall(principal.householdId(), callId);
        requireLive(call);
        CallParticipant me = participantOf(callId, principal.userId());
        if (me == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不在该通话中");
        if (Calls.STATE_JOINED.equals(me.getState())) return snapshot(call);
        if (!Calls.STATE_INVITED.equals(me.getState()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "通话状态已变化，无法接听");

        int max = effectiveMaxParticipants();
        if (countJoined(callId) + 1 > max)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "通话人数已满");

        LocalDateTime now = LocalDateTime.now();
        int updated =
            participants.update(
                null,
                new LambdaUpdateWrapper<CallParticipant>()
                    .eq(CallParticipant::getId, me.getId())
                    .eq(CallParticipant::getState, Calls.STATE_INVITED)
                    .set(CallParticipant::getState, Calls.STATE_JOINED)
                    .set(CallParticipant::getJoinedAt, now)
                    .set(CallParticipant::getLeftAt, null));
        if (updated == 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "通话状态已变化，请重试");

        if (Calls.STATUS_RINGING.equals(call.getStatus())
            && sessions.update(
            null,
            new LambdaUpdateWrapper<CallSession>()
                .eq(CallSession::getId, callId)
                .eq(CallSession::getStatus, Calls.STATUS_RINGING)
                .set(CallSession::getStatus, Calls.STATUS_ACTIVE)
                .set(CallSession::getAnsweredAt, now))
            > 0) {
            call.setStatus(Calls.STATUS_ACTIVE);
            call.setAnsweredAt(now);
        }
        cacheMembership(callId, principal.userId());

        CallVO snapshot = snapshot(call);
        List<Long> recipients = liveUserIds(callId);
        long userId = principal.userId();
        afterCommit(
            () ->
                bus.broadcast(
                    "call.accepted",
                    callId,
                    call.getHouseholdId(),
                    userId,
                    recipients,
                    Map.of("call", snapshot, "user", userId)));
        return snapshot;
    }

    @Override
    @Transactional
    public void reject(SessionPrincipal principal, String callId) {
        CallSession call = requireCall(principal.householdId(), callId);
        if (Calls.STATUS_ENDED.equals(call.getStatus())) return;
        CallParticipant me = participantOf(callId, principal.userId());
        if (me == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不在该通话中");
        if (Calls.STATE_JOINED.equals(me.getState()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已接听，请使用挂断");
        if (!Calls.STATE_INVITED.equals(me.getState())) return; // 幂等

        participants.update(
            null,
            new LambdaUpdateWrapper<CallParticipant>()
                .eq(CallParticipant::getId, me.getId())
                .eq(CallParticipant::getState, Calls.STATE_INVITED)
                .set(CallParticipant::getState, Calls.STATE_REJECTED)
                .set(CallParticipant::getLeftAt, LocalDateTime.now()));

        if (Calls.MODE_DIRECT.equals(call.getMode())) {
            close(call, Calls.REASON_REJECTED);
            return;
        }
        long userId = principal.userId();
        CallVO snapshot = snapshot(call);
        List<Long> recipients = liveUserIds(callId);
        afterCommit(
            () ->
                bus.broadcast(
                    "call.rejected",
                    callId,
                    call.getHouseholdId(),
                    userId,
                    recipients,
                    Map.of("call", snapshot, "user", userId)));
    }

    @Override
    @Transactional
    public void leave(SessionPrincipal principal, String callId) {
        CallSession call = requireCall(principal.householdId(), callId);
        if (Calls.STATUS_ENDED.equals(call.getStatus())) return;
        CallParticipant me = participantOf(callId, principal.userId());
        if (me == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不在该通话中");
        if (Calls.STATE_INVITED.equals(me.getState())) {
            reject(principal, callId);
            return;
        }
        if (!Calls.STATE_JOINED.equals(me.getState())) return; // 已离开，幂等
        detach(call, me);
    }

    @Override
    @Transactional
    public void end(SessionPrincipal principal, String callId) {
        CallSession call = requireCall(principal.householdId(), callId);
        if (Calls.STATUS_ENDED.equals(call.getStatus())) return;
        if (!principal.admin() && !Objects.equals(call.getCallerId(), principal.userId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有发起者可以结束整通通话");
        close(call, Calls.REASON_HANGUP);
    }

    @Override
    @Transactional
    public void updateMediaState(
        SessionPrincipal principal, String callId, boolean micOn, boolean camOn) {
        CallSession call = requireCall(principal.householdId(), callId);
        if (Calls.STATUS_ENDED.equals(call.getStatus())) return;
        CallParticipant me = participantOf(callId, principal.userId());
        if (me == null || !Calls.STATE_JOINED.equals(me.getState())) return;
        participants.update(
            null,
            new LambdaUpdateWrapper<CallParticipant>()
                .eq(CallParticipant::getId, me.getId())
                .set(CallParticipant::getMicOn, micOn)
                .set(CallParticipant::getCamOn, camOn));
        long userId = principal.userId();
        List<Long> recipients = liveUserIds(callId);
        afterCommit(
            () ->
                bus.broadcast(
                    "call.state",
                    callId,
                    call.getHouseholdId(),
                    userId,
                    recipients,
                    Map.of("user", userId, "mic", micOn, "cam", camOn)));
    }

    @Override
    @Transactional
    public CallVO setRecording(SessionPrincipal principal, String callId, boolean on) {
        CallSession call = requireCall(principal.householdId(), callId);
        requireLive(call);
        CallParticipant me = participantOf(callId, principal.userId());
        // 管理员需要能在出问题时关掉录音，否则只能等录音的人自己停
        if (!principal.admin() && (me == null || !Calls.STATE_JOINED.equals(me.getState())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有通话中的成员可以开关录音");
        if (!on && call.getRecordedBy() != null && !Objects.equals(call.getRecordedBy(), principal.userId())
                && !principal.admin())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能停止自己发起的录音");

        long userId = principal.userId();
        // 两个人都点「开始录音」时，只保留先开的那个：否则谁停止、
        // 谁的音频被上传都没有确定答案，状态字段也只剩一个
        if (on && Boolean.TRUE.equals(call.getRecording()) && !Objects.equals(call.getRecordedBy(), userId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已经有成员在录音");
        Long owner = on ? userId : null;
        sessions.update(
            null,
            new LambdaUpdateWrapper<CallSession>()
                .eq(CallSession::getId, callId)
                .set(CallSession::getRecording, on)
                .set(CallSession::getRecordedBy, owner));
        call.setRecording(on);
        call.setRecordedBy(owner);

        CallVO snapshot = snapshot(call);
        List<Long> recipients = liveUserIds(callId);
        afterCommit(
            () ->
                bus.broadcast(
                    "call.record",
                    callId,
                    call.getHouseholdId(),
                    userId,
                    recipients,
                    Map.of("call", snapshot, "recording", on, "by", userId)));
        return snapshot;
    }

    @Override
    @Transactional(readOnly = true)
    public CallVO detail(SessionPrincipal principal, String callId) {
        CallSession call = requireCall(principal.householdId(), callId);
        if (participantOf(callId, principal.userId()) == null && !principal.admin())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不在该通话中");
        return snapshot(call);
    }

    @Override
    @Transactional(readOnly = true)
    public CallVO active(SessionPrincipal principal) {
        CallSession call = activeFor(principal.userId(), principal.householdId());
        return call == null ? null : snapshot(call);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CallVO> history(SessionPrincipal principal, PageQuery page) {
        // 家庭过滤必须发生在分页之前：先按参与者 ID 截断再过滤家庭，跨家庭的同 ID 记录会占掉页内名额，
        // 表现为某页为空或漏掉本家庭记录。用 EXISTS 直接把「本家庭 + 我参与」下推到 SQL。
        List<CallSession> own =
            sessions.selectList(
                new LambdaQueryWrapper<CallSession>()
                    .eq(CallSession::getHouseholdId, principal.householdId())
                    .apply(
                        "EXISTS (SELECT 1 FROM call_participant p WHERE p.call_id = call_session.id AND p.user_id = {0})",
                        principal.userId())
                    .orderByDesc(CallSession::getCreatedAt)
                    .last(page.limitClause()));
        return own.stream().map(this::snapshot).toList();
    }

    @Override
    public boolean canSignal(String callId, long userId) {
        String key = callId + ":" + userId;
        Long expiry = membershipCache.get(key);
        if (expiry != null && expiry > System.currentTimeMillis()) return true;
        CallParticipant pt = participantOf(callId, userId);
        if (pt == null) return false;
        cacheMembership(callId, userId);
        return true;
    }

    @Override
    public List<IceServerVO> iceServers(SessionPrincipal principal) {
        return iceServers.forUser(principal.userId());
    }

    @Override
    @Transactional
    public int sweep() {
        LocalDateTime now = LocalDateTime.now();
        int handled = 0;

        LocalDateTime ringDeadline = now.minusSeconds(Math.max(10, properties.getRingTimeoutSeconds()));
        List<CallSession> ringing =
            sessions.selectList(
                new LambdaQueryWrapper<CallSession>()
                    .eq(CallSession::getStatus, Calls.STATUS_RINGING)
                    .lt(CallSession::getCreatedAt, ringDeadline)
                    .last("LIMIT 50"));
        for (CallSession call : ringing) {
            close(call, Calls.REASON_NO_ANSWER);
            handled++;
        }

        // 空房间兜底：所有参与者都已离开或拒绝，但会话仍是 ACTIVE（如异常断连）。
        List<CallSession> active =
            sessions.selectList(
                new LambdaQueryWrapper<CallSession>()
                    .eq(CallSession::getStatus, Calls.STATUS_ACTIVE)
                    .lt(CallSession::getAnsweredAt, now.minusSeconds(5))
                    .last("LIMIT 50"));
        for (CallSession call : active) {
            if (countJoined(call.getId()) == 0) {
                close(call, Calls.REASON_LEFT);
                handled++;
            }
        }
        membershipCache.entrySet().removeIf(entry -> entry.getValue() <= System.currentTimeMillis());
        return handled;
    }

    @Override
    @Transactional
    public void leaveIfDisconnected(long userId, long householdId) {
        CallSession call = activeFor(userId, householdId);
        if (call == null) return;
        CallParticipant me = participantOf(call.getId(), userId);
        if (me != null && Calls.STATE_JOINED.equals(me.getState())) detach(call, me);
    }

    // ---------------------------------------------------------------- 内部方法

    /**
     * 人数上限随媒体栈切换：mesh 下每端上行 N-1 路，8 人是音视频可用上限；
     * SFU 下每端只推 1 路，由 LiveKit 侧配置决定（默认 20）。
     * 关掉 feature flag 后立刻回落到原上限，无需改数据。
     */
    private int effectiveMaxParticipants() {
        int configured =
                liveKit.isEnabled()
                        ? liveKit.getMaxParticipants()
                        : properties.getMaxParticipants();
        return Math.max(2, configured);
    }

    private void detach(CallSession call, CallParticipant me) {
        participants.update(
            null,
            new LambdaUpdateWrapper<CallParticipant>()
                .eq(CallParticipant::getId, me.getId())
                .eq(CallParticipant::getState, Calls.STATE_JOINED)
                .set(CallParticipant::getState, Calls.STATE_LEFT)
                .set(CallParticipant::getLeftAt, LocalDateTime.now()));
        membershipCache.remove(call.getId() + ":" + me.getUserId());

        if (Objects.equals(call.getCallerId(), me.getUserId())) {
            close(call, Calls.REASON_HANGUP);
            return;
        }
        if (countJoined(call.getId()) == 0) {
            close(call, Calls.REASON_LEFT);
            return;
        }
        long userId = me.getUserId();
        CallVO snapshot = snapshot(call);
        List<Long> recipients = liveUserIds(call.getId());
        afterCommit(
            () ->
                bus.broadcast(
                    "call.left",
                    call.getId(),
                    call.getHouseholdId(),
                    userId,
                    recipients,
                    Map.of("call", snapshot, "user", userId)));
    }

    /**
     * 结束会话并通知所有相关方；条件更新保证重复调用只生效一次。
     */
    private void close(CallSession call, String reason) {
        LocalDateTime now = LocalDateTime.now();
        int updated =
            sessions.update(
                null,
                new LambdaUpdateWrapper<CallSession>()
                    .eq(CallSession::getId, call.getId())
                    .in(CallSession::getStatus, Calls.STATUS_RINGING, Calls.STATUS_ACTIVE)
                    .set(CallSession::getStatus, Calls.STATUS_ENDED)
                    .set(CallSession::getEndReason, reason)
                    .set(CallSession::getEndedAt, now));
        if (updated == 0) return;
        call.setStatus(Calls.STATUS_ENDED);
        call.setEndReason(reason);
        call.setEndedAt(now);

        String pending =
            Calls.REASON_NO_ANSWER.equals(reason) ? Calls.STATE_MISSED : Calls.STATE_LEFT;
        for (CallParticipant pt : participantsOf(call.getId())) {
            if (Calls.STATE_INVITED.equals(pt.getState())) {
                pt.setState(pending);
                pt.setLeftAt(now);
            } else if (Calls.STATE_JOINED.equals(pt.getState())) {
                pt.setState(Calls.STATE_LEFT);
                pt.setLeftAt(now);
            } else {
                continue;
            }
            participants.updateById(pt);
        }

        CallVO snapshot = snapshot(call);
        List<Long> recipients = participantsOf(call.getId()).stream()
            .map(CallParticipant::getUserId)
            .toList();
        membershipCache.keySet().removeIf(key -> key.startsWith(call.getId() + ":"));
        afterCommit(
            () ->
                bus.broadcast(
                    "call.ended",
                    call.getId(),
                    call.getHouseholdId(),
                    call.getCallerId(),
                    recipients,
                    Map.of("call", snapshot, "reason", reason)));
    }

    private LinkedHashSet<Long> resolveTargets(CallStartDTO dto) {
        LinkedHashSet<Long> targets = new LinkedHashSet<>();
        boolean direct = Calls.MODE_DIRECT.equals(dto.mode()) || dto.participantIds() == null || dto.participantIds().size() <= 1;
        if (direct) {
            if (dto.calleeId() == null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择通话对象");
            targets.add(dto.calleeId());
        } else {
            if (dto.participantIds().isEmpty())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一位家人");
            targets.addAll(dto.participantIds());
        }
        targets.remove(null);
        return targets;
    }

    private void verifyMembers(String bearer, Set<Long> targets) {
        if (!properties.isVerifyMembers()) return;
        UserFeignClient client = userClient.getIfAvailable();
        if (client == null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "成员校验服务不可用");
        ApiResponse<List<MemberApiVO>> response;
        try {
            response = client.members(bearer);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "成员服务暂时不可用，请稍后重试");
        }
        if (response == null || response.code() != 0 || response.data() == null)
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                response == null || response.msg() == null ? "成员服务暂时不可用" : response.msg());
        Set<Long> members =
            response.data().stream().map(MemberApiVO::id).collect(Collectors.toSet());
        if (!members.containsAll(targets))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能邀请同一家庭的成员");
    }

    private CallSession requireCall(long householdId, String callId) {
        CallSession call = sessions.selectById(callId);
        if (call == null || !Objects.equals(call.getHouseholdId(), householdId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通话不存在");
        return call;
    }

    private void requireLive(CallSession call) {
        if (Calls.STATUS_ENDED.equals(call.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "通话已结束");
    }

    private CallParticipant participantOf(String callId, long userId) {
        return participants.selectOne(
            new LambdaQueryWrapper<CallParticipant>()
                .eq(CallParticipant::getCallId, callId)
                .eq(CallParticipant::getUserId, userId));
    }

    private List<CallParticipant> participantsOf(String callId) {
        return participants.selectList(
            new LambdaQueryWrapper<CallParticipant>()
                .eq(CallParticipant::getCallId, callId)
                .orderByAsc(CallParticipant::getId));
    }

    private void participant(String callId, long userId, String state, LocalDateTime now) {
        var pt = new CallParticipant();
        pt.setCallId(callId);
        pt.setUserId(userId);
        pt.setState(state);
        pt.setMicOn(true);
        pt.setCamOn(true);
        pt.setInvitedAt(now);
        pt.setJoinedAt(Calls.STATE_JOINED.equals(state) ? now : null);
        participants.insert(pt);
        cacheMembership(callId, userId);
    }

    private long countJoined(String callId) {
        return participants.selectCount(
            new LambdaQueryWrapper<CallParticipant>()
                .eq(CallParticipant::getCallId, callId)
                .eq(CallParticipant::getState, Calls.STATE_JOINED));
    }

    private List<Long> liveUserIds(String callId) {
        return participantsOf(callId).stream()
            .filter(pt -> Calls.STATE_JOINED.equals(pt.getState()) || Calls.STATE_INVITED.equals(pt.getState()))
            .map(CallParticipant::getUserId)
            .toList();
    }

    private CallSession activeFor(long userId, long householdId) {
        List<CallParticipant> mine =
            participants.selectList(
                new LambdaQueryWrapper<CallParticipant>()
                    .eq(CallParticipant::getUserId, userId)
                    .in(CallParticipant::getState, Calls.STATE_INVITED, Calls.STATE_JOINED)
                    .orderByDesc(CallParticipant::getId)
                    .last("LIMIT 10"));
        for (CallParticipant pt : mine) {
            CallSession call = sessions.selectById(pt.getCallId());
            if (call == null) continue;
            if (!Objects.equals(call.getHouseholdId(), householdId)) continue;
            if (Calls.STATUS_RINGING.equals(call.getStatus()) || Calls.STATUS_ACTIVE.equals(call.getStatus()))
                return call;
        }
        return null;
    }

    private CallVO snapshot(CallSession call) {
        List<CallParticipantVO> people =
            participantsOf(call.getId()).stream()
                .map(
                    pt ->
                        new CallParticipantVO(
                            pt.getUserId(),
                            pt.getState(),
                            Boolean.TRUE.equals(pt.getMicOn()),
                            Boolean.TRUE.equals(pt.getCamOn()),
                            pt.getJoinedAt()))
                .toList();
        return new CallVO(
            call.getId(),
            call.getHouseholdId(),
            call.getMode(),
            call.getKind(),
            call.getStatus(),
            call.getCallerId(),
            call.getEndReason(),
            Boolean.TRUE.equals(call.getRecording()),
            call.getRecordedBy(),
            call.getCreatedAt(),
            call.getAnsweredAt(),
            call.getEndedAt(),
            people);
    }

    private void cacheMembership(String callId, long userId) {
        // 缓存大小超限时先清理过期项
        if (membershipCache.size() >= MAX_MEMBERSHIP_CACHE_SIZE) {
            long now = System.currentTimeMillis();
            membershipCache.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        membershipCache.put(callId + ":" + userId, System.currentTimeMillis() + MEMBERSHIP_TTL_MS);
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        action.run();
                    } catch (RuntimeException ex) {
                        log.warn("通话信令发送失败", ex);
                    }
                }
            });
    }
}
