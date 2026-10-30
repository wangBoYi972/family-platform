package com.familyhome.media.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 通话对外视图；不含 SDP / ICE 候选等媒体细节。 */
public record CallVO(
        String id,
        Long householdId,
        String mode,
        String kind,
        String status,
        Long callerId,
        String endReason,
        LocalDateTime createdAt,
        LocalDateTime answeredAt,
        LocalDateTime endedAt,
        List<CallParticipantVO> participants) {}
