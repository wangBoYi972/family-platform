package com.familyhome.ai.dto;

/**
 * 管理端看到的 AI 配置状态：只回 key 的首三尾四，整串 key 不出服务端。
 *
 * <ul>
 *   <li>{@code enabled}：开关状态</li>
 *   <li>{@code ready}：开关开着且 key 非空，也就是「现在真的能对话」</li>
 *   <li>{@code maskedKey}：尾号，用于让管理员认出用的是哪把 key；未配置时为空串</li>
 *   <li>{@code source}：{@code admin}（管理端保存）或 {@code env}（环境变量）</li>
 * </ul>
 */
public record AiSettingsVO(
        boolean enabled,
        boolean ready,
        String maskedKey,
        String source,
        String model,
        String baseUrl) {}
