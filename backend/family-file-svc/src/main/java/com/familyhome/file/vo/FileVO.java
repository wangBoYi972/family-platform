package com.familyhome.file.vo;

import java.time.LocalDateTime;

public record FileVO(
        String id,
        Long ownerId,
        String name,
        String mime,
        Long sizeBytes,
        String visibility,
        LocalDateTime createdAt) {}
