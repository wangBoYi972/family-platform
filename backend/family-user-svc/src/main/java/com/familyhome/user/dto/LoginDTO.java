package com.familyhome.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LoginDTO(
        @NotBlank @Pattern(regexp = "^1[0-9]{10}$") String phone,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code,
        String client) {}
