package com.familyhome.content.vo;

public record EntryVO(
        Long id,
        Long creatorId,
        String title,
        String body,
        java.time.LocalDate eventDate,
        Boolean yearly,
        Boolean pinned,
        String visibility,
        String fileIds,
        Integer likes,
        java.time.LocalDateTime createdAt) {}
