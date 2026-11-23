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
        /** 通话中是否正在录音（所有参与者都能看到）。 */
        Boolean recording,
        /** 发起录音的用户；未录音时为 null。 */
        Long recordedBy,
        LocalDateTime createdAt,
        LocalDateTime answeredAt,
        LocalDateTime endedAt,
        List<CallParticipantVO> participants) {}
