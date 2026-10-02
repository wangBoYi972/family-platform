package com.familyhome.content.vo;

public record TaskVO(
        Long id,
        String title,
        Long creatorId,
        Long assigneeId,
        String assigneeName,
        java.time.LocalDate deadline,
        String status,
        java.time.LocalDateTime createdAt) {}
