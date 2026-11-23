package com.familyhome.archive.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 用药计划出参：只回明文正文（药名/剂量/备注）与必要的时间字段。 */
public record MedicationPlanVO(
        String id,
        Long ownerId,
        String name,
        String dosage,
        String note,
        List<String> times,
        LocalDate startDate,
        LocalDate endDate,
        String visibility,
        boolean enabled,
        LocalDateTime createdAt) {}
