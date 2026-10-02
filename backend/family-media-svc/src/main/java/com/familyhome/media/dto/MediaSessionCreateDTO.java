package com.familyhome.media.dto;

import jakarta.validation.constraints.*;

public record MediaSessionCreateDTO(@NotBlank @Pattern(regexp = "AUDIO|VIDEO") String kind) {}
