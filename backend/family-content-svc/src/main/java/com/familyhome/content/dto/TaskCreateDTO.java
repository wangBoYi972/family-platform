package com.familyhome.content.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record TaskCreateDTO(
        @NotBlank @Size(max = 80) String title, LocalDate deadline, @Positive Long assigneeId) {}
