package com.familyhome.archive.vo;

import java.time.LocalDateTime;

public record ArchiveVO(
        String id,
        Long ownerId,
        String title,
        String category,
        String visibility,
        LocalDateTime createdAt) {}
