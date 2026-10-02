package com.familyhome.user.dto;

import jakarta.validation.constraints.*;

public record AccountCreateDTO(
        @NotBlank @Pattern(regexp = "^1[0-9]{10}$") String phone,
        @NotBlank @Pattern(regexp = "ADMIN|MEMBER") String role,
        @NotBlank @Size(min = 12, max = 64) String password) {
    @Override
    public String toString() {
        return "AccountCreateDTO[凭据已隐藏]";
    }
}
