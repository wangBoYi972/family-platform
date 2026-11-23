package com.familyhome.media.vo;

import java.time.LocalDateTime;

/**
 * 录音与纪要视图。
 *
 * <p>列表接口不下发 {@code transcript} / {@code minutes}：整篇文本可能有几万字，
 * 一次拉 30 条会把响应撑到几 MB。详情接口才带全文。
 */
public record CallRecordingVO(
        String id,
        String callId,
        String title,
        String status,
        Integer durationMs,
        Long recordedBy,
        Boolean consentConfirmed,
        Integer segmentCount,
        String transcript,
        String minutes,
        String error,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
