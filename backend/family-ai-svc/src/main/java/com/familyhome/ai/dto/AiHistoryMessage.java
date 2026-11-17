package com.familyhome.ai.dto;

import java.time.LocalDateTime;

/** 返回给客户端的一条助手历史。不含家庭和用户标识，调用方只能看到自己的。 */
public record AiHistoryMessage(long id, String role, String content, LocalDateTime createdAt) {}
