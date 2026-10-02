package com.familyhome.content.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record EntrySaveDTO(
        @NotBlank @Size(max = 120) String title,
        @Size(max = 10000) String body,
        LocalDate eventDate,
        boolean yearly,
        boolean pinned,
        @Pattern(regexp = "FAMILY|PRIVATE") String visibility,
        @Size(max = 2000) String fileIds) {}
