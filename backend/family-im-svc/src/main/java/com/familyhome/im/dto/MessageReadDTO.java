package com.familyhome.im.dto;

import jakarta.validation.constraints.*;

public record MessageReadDTO(@Positive long messageId) {}
