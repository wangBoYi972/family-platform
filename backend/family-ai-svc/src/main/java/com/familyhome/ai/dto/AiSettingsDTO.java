package com.familyhome.ai.dto;

import jakarta.validation.constraints.Size;

/**
 * 管理端保存 AI 助手配置的请求。
 *
 * <p>{@code apiKey} 为空表示「只改开关，不动已保存的 key」：管理员临时关掉再打开时
 * 不必重新粘贴一遍完整 key——接口不回显 key，要求重填等于逼用户重新找 key。
 * {@code apiKey} 与 {@code enabled} 都在 {@link #toString()} 里脱敏，避免被误打进日志。
 */
public record AiSettingsDTO(boolean enabled, @Size(max = 512) String apiKey) {

    @Override
    public String toString() {
        return "AiSettingsDTO[enabled=" + enabled
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "未修改" : "已提供") + "]";
    }
}
