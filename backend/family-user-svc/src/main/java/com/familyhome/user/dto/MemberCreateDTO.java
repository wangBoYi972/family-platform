package com.familyhome.user.dto;

import jakarta.validation.constraints.*;

public record MemberCreateDTO(
        @NotBlank @Pattern(regexp = "^1[0-9]{10}$") String phone,
        @NotBlank @Pattern(regexp = "ADMIN|MEMBER") String role) {}
