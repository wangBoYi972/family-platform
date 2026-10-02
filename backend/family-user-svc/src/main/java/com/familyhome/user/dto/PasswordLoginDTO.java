package com.familyhome.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordLoginDTO(
        @NotBlank @Pattern(regexp = "^1[0-9]{10}$") String phone,
        @NotBlank @Size(max = 64) String password,
        @NotBlank @Pattern(regexp = "admin|family|archive") String client) {
    @Override
    public String toString() {
        return "PasswordLoginDTO[凭据已隐藏]";
    }
}
