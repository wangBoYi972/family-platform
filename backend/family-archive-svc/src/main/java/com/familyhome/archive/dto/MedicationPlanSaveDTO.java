package com.familyhome.archive.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

/** 用药计划入参。家庭编号不在这里，服务端只从会话取。 */
public record MedicationPlanSaveDTO(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 80) String dosage,
        @Size(max = 500) String note,
        @NotEmpty @Size(max = 8) List<@Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d") String> times,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @NotBlank @Pattern(regexp = "PRIVATE|FAMILY") String visibility,
        Boolean enabled) {}
