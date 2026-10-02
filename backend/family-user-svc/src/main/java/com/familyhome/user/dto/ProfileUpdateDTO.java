package com.familyhome.user.dto;

import jakarta.validation.constraints.*;

public record ProfileUpdateDTO(@NotBlank @Size(max = 64) String displayName) {}
