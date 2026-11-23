package com.familyhome.archive.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;

/** 复诊入参；标题、医院科室、备注都按健康内容加密。 */
public record FollowUpVisitSaveDTO(
        @NotBlank @Size(max = 120) String title,
        @Size(max = 120) String hospital,
        @Size(max = 500) String note,
        @NotNull LocalDateTime visitAt,
        @Min(0) @Max(30) Integer remindDays,
        @NotBlank @Pattern(regexp = "PRIVATE|FAMILY") String visibility) {}
