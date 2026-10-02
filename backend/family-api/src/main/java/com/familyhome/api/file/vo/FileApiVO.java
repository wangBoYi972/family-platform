package com.familyhome.api.file.vo;

public record FileApiVO(
        String id,
        Long householdId,
        Long ownerId,
        String name,
        String mime,
        Long sizeBytes,
        String visibility) {}
