package com.familyhome.im.vo;

import java.time.LocalDateTime;

public record MessageVO(
        Long id,
        String conversationId,
        Long senderId,
        String clientId,
        String kind,
        String body,
        LocalDateTime createdAt) {}
