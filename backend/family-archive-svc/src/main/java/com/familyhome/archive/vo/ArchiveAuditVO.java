package com.familyhome.archive.vo;

import java.time.LocalDateTime;

public record ArchiveAuditVO(Long userId, String action, LocalDateTime createdAt) {}
