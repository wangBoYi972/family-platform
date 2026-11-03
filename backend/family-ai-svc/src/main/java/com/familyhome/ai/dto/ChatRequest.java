package com.familyhome.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 一次 AI 对话请求：历史由前端保存并回传（服务端无状态），服务端只保留最近若干轮。 */
public record ChatRequest(@NotNull @Valid @Size(max = 40) List<Message> messages) {

    public record Message(
            @NotBlank @Size(max = 20) String role,
            @NotBlank @Size(max = 8000) String content) {

        @Override
        public String toString() {
            return "Message[role=" + role + ", content.length=" + (content == null ? 0 : content.length()) + "]";
        }
    }
}
