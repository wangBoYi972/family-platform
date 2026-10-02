package com.familyhome.im.dto;

import jakarta.validation.constraints.*;

public record MessageSendDTO(
        @NotBlank @Pattern(regexp = "[a-f0-9-]{36}") String clientId,
        @NotBlank @Pattern(regexp = "TEXT|IMAGE|AUDIO|FILE") String kind,
        @NotBlank @Size(max = 4000) String body) {}
