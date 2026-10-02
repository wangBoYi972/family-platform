package com.familyhome.archive.dto;

import jakarta.validation.constraints.*;

public record ArchiveSaveDTO(
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Pattern(regexp = "IDENTITY|HEALTH|DOCUMENT") String category,
        @NotBlank @Size(max = 20000) String body,
        @NotBlank @Pattern(regexp = "PRIVATE|FAMILY") String visibility) {}
