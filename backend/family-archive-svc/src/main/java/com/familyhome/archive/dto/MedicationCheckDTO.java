package com.familyhome.archive.dto;

import jakarta.validation.constraints.NotBlank;

/** 勾选服药：日期由服务端按家庭时区取当天，客户端只报时刻。 */
public record MedicationCheckDTO(@NotBlank String time) {}
