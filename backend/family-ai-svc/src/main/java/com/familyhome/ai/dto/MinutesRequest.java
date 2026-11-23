package com.familyhome.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 由通话转写稿生成纪要。participants 只用于让模型知道「谁是谁」，
 * 不传则按「家人」泛称，避免编造称呼。
 */
public record MinutesRequest(
        @Size(max = 80) String title,
        @Size(max = 16) List<@Size(max = 40) String> participants,
        @NotBlank @Size(max = 60000) String transcript,
        Integer durationMs) {

    @Override
    public String toString() {
        return "MinutesRequest[transcript.length=" + (transcript == null ? 0 : transcript.length()) + "]";
    }
}
