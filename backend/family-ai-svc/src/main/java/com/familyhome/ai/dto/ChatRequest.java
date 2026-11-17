package com.familyhome.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 一次 AI 对话请求。服务端会把新的一轮写入历史；这里仍带回最近若干条，给模型当上下文。
 * clientId 可选：同一键的提问只落库一次，避免超时重试插出两行。
 */
public record ChatRequest(
        @NotNull @Valid @Size(max = 40) List<Message> messages,
        @Size(max = 36) @Pattern(regexp = "[a-f0-9-]{36}") String clientId) {

    public ChatRequest(List<Message> messages) {
        this(messages, null);
    }

    public record Message(
            @NotBlank @Size(max = 20) String role,
            @NotBlank @Size(max = 8000) String content) {

        @Override
        public String toString() {
            return "Message[role=" + role + ", content.length=" + (content == null ? 0 : content.length()) + "]";
        }
    }
}
