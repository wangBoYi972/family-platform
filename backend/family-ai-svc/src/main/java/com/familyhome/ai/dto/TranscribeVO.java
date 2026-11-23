package com.familyhome.ai.dto;

/** 转写结果。{@code empty} 表示模型没识别到语音，前端据此提示而不是当成失败。 */
public record TranscribeVO(String text, String model, boolean empty) {}
