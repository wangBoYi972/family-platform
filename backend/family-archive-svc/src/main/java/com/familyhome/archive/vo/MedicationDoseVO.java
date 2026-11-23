package com.familyhome.archive.vo;

/** 当天某次服药：药品用途之外的医疗描述不下发。 */
public record MedicationDoseVO(
        String planId,
        String name,
        String dosage,
        String time,
        boolean taken,
        Long checkedBy) {}
