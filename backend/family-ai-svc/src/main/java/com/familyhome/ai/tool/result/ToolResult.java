package com.familyhome.ai.tool.result;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 工具执行结果。
 *
 * <p>成功时 {@code success=true}，{@code data} 是查询到的 JSON 字符串；
 * 失败时 {@code success=false}，{@code error} 是错误描述。
 *
 * <p>无论成功失败，都返回结构化对象而不是抛异常，让模型能向用户解释「查不到」。
 *
 * @author family-platform
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResult(
        boolean success,
        String data,
        String error
) {
    public static ToolResult success(String data) {
        return new ToolResult(true, data, null);
    }

    public static ToolResult failure(String error) {
        return new ToolResult(false, null, error);
    }

    /**
     * 转成模型能读的 JSON 字符串。
     * 成功时直接返回 data（下游服务的 JSON），失败时返回 {"error": "..."}。
     */
    public String toModelJson() {
        if (success) {
            return data;
        }
        return "{\"error\":\"" + error.replace("\"", "\\\"") + "\"}";
    }
}
