package com.familyhome.ai.tool;

import com.familyhome.ai.tool.definition.ToolDefinition;
import com.familyhome.ai.tool.result.ToolResult;

/**
 * AI 工具接口。
 *
 * <p>每个工具负责：
 * <ul>
 *   <li>提供 OpenAI function calling 格式的定义
 *   <li>解析模型传来的参数
 *   <li>执行实际查询逻辑
 *   <li>返回结构化结果
 * </ul>
 *
 * <p>实现类应该是无状态的 Spring Bean，通过 {@link ToolRegistry} 注册。
 *
 * @author family-platform
 */
public interface Tool {

    /**
     * 工具的唯一标识，对应 OpenAI function calling 里的 function name。
     */
    String getName();

    /**
     * 工具定义，用于告诉模型这个工具是干什么的、需要什么参数。
     */
    ToolDefinition getDefinition();

    /**
     * 执行工具调用。
     *
     * @param argumentsJson 模型传来的参数（JSON 字符串）
     * @param bearer 用户的 Authorization header（含 "Bearer " 前缀）
     * @return 执行结果，成功或失败都包装在 ToolResult 里
     */
    ToolResult execute(String argumentsJson, String bearer);
}
