package com.familyhome.media.vo;

import java.time.LocalDateTime;

public record MediaSessionVO(
        String id,
        Long householdId,
        Long creatorId,
        String kind,
        String status,
        LocalDateTime createdAt) {}
