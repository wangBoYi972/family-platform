package com.familyhome.user.dto;

import jakarta.validation.constraints.*;

/** 管理员发放邀请码的请求。次数与有效期留空时取默认：一人一次、长期有效。 */
public record InviteCodeCreateDTO(
        @Pattern(regexp = "ADMIN|MEMBER") String role,
        @Min(1) @Max(10) Integer maxUses,
        @Min(1) @Max(720) Integer expiresInHours) {}
