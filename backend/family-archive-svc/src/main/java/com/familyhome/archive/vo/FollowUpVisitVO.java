package com.familyhome.archive.vo;

import java.time.LocalDateTime;

/** 复诊出参：明文字段即加密正文的还原结果，不附加其它医疗描述。 */
public record FollowUpVisitVO(
        String id,
        Long ownerId,
        String title,
        String hospital,
        String note,
        LocalDateTime visitAt,
        int remindDays,
        String visibility,
        LocalDateTime createdAt) {}
