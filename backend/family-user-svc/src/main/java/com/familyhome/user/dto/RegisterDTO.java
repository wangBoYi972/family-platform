package com.familyhome.user.dto;

import jakarta.validation.constraints.*;

/** 自助注册请求：邀请码是必填项，缺失或无效一律拒绝创建账号。 */
public record RegisterDTO(
        @NotBlank @Pattern(regexp = "^1[0-9]{10}$") String phone,
        @NotBlank @Size(min = 12, max = 64) String password,
        @NotBlank @Size(max = 24) String inviteCode,
        @Size(max = 32) String displayName,
        @Pattern(regexp = "admin|family|archive") String client) {
    @Override
    public String toString() {
        return "RegisterDTO[phone=" + phone + ", 凭据与邀请码已隐藏]";
    }
}
