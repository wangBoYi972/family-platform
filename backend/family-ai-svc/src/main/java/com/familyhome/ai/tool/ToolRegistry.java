package com.familyhome.ai.tool;

import com.familyhome.ai.tool.definition.ToolDefinition;
import com.familyhome.ai.tool.result.ToolResult;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心。
 *
 * <p>负责：
 * <ul>
 *   <li>管理所有可用工具
 *   <li>提供工具定义列表（发给模型）
 *   <li>根据名称分发工具调用
 * </ul>
 *
 * <p>实现类通过构造函数注入所有 {@link Tool} Bean，自动注册。
 *
 * @author family-platform
 */
@Component
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    @Autowired
    private List<Tool> toolList;

    @PostConstruct
    public void init() {
        for (Tool tool : toolList) {
            tools.put(tool.getName(), tool);
            log.info("注册 AI 工具：{}", tool.getName());
        }
    }

    /**
     * 获取所有工具的定义（用于每次对话请求）。
     */
    public List<ToolDefinition> getDefinitions() {
        return tools.values().stream()
                .map(Tool::getDefinition)
                .toList();
    }

    /**
     * 执行一次工具调用。
     *
     * @param name 工具名称
     * @param argumentsJson 参数 JSON
     * @param bearer 用户 token
     * @return 执行结果（成功或失败都包装在 ToolResult 里）
     */
    public ToolResult execute(String name, String argumentsJson, String bearer) {
        Tool tool = tools.get(name);
        if (tool == null) {
            log.warn("未知工具：{}", name);
            return ToolResult.failure("未知工具: " + name);
        }
        try {
            return tool.execute(argumentsJson, bearer);
        } catch (RuntimeException e) {
            log.error("工具调用失败：{}, 参数：{}", name, argumentsJson, e);
            return ToolResult.failure("查询失败: " + e.getMessage());
        }
    }
}
