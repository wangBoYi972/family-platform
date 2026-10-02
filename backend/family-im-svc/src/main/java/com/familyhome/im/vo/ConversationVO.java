package com.familyhome.im.vo;

import java.time.LocalDateTime;

public record ConversationVO(
        String id,
        String kind,
        Long userA,
        Long userB,
        String title,
        long unread,
        LocalDateTime createdAt) {}
