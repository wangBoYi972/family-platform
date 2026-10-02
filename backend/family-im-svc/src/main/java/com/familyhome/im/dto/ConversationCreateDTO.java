package com.familyhome.im.dto;

import jakarta.validation.constraints.*;

public record ConversationCreateDTO(@Positive Long peerId) {}
