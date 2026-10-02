package com.familyhome.content.dto;

import jakarta.validation.constraints.*;

public record TaskStatusDTO(
        @NotBlank @Pattern(regexp = "TODO|IN_PROGRESS|COMPLETED") String status) {}
