package com.familyhome.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordChangeDTO(
        @NotBlank @Size(max = 64) String currentPassword,
        @NotBlank @Size(min = 12, max = 64) String newPassword) {
    @Override
    public String toString() {
        return "PasswordChangeDTO[凭据已隐藏]";
    }
}
