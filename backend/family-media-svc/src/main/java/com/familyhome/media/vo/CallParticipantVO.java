package com.familyhome.media.vo;

import java.time.LocalDateTime;

public record CallParticipantVO(
        Long userId, String state, boolean micOn, boolean camOn, LocalDateTime joinedAt) {}
