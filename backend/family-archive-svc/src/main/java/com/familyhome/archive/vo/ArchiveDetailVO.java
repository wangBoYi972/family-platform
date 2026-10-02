package com.familyhome.archive.vo;

import java.time.LocalDateTime;

public record ArchiveDetailVO(
        String id,
        Long ownerId,
        String title,
        String category,
        String visibility,
        String body,
        LocalDateTime createdAt) {}
