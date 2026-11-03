package com.familyhome.ai.tool.impl;

import com.familyhome.ai.client.ContentServiceClient;
import com.familyhome.ai.tool.Tool;
import com.familyhome.ai.tool.definition.ToolDefinition;
import com.familyhome.ai.tool.result.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 查询任务清单工具。
 *
 * <p>支持按状态筛选（TODO / DOING / DONE），默认返回全部。
 *
 * @author family-platform
 */
@Component
public class ListTasksTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(ListTasksTool.class);
    private static final String NAME = "list_tasks";

    @Autowired
    private ContentServiceClient contentClient;

    @Autowired
    private ObjectMapper json;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.of(
                NAME,
                "查询本家庭的任务清单（按时间倒序，最多 20 条）。用于回答「有什么任务」「谁还没完成」。",
                ToolDefinition.ParametersSpec.object(
                        Map.of("status", ToolDefinition.PropertySpec.stringEnum(
                                "按状态筛选：TODO 待办 / DOING 进行中 / DONE 已完成；不传返回全部",
                                "TODO", "DOING", "DONE"
                        ))
                )
        );
    }

    @Override
    public ToolResult execute(String argumentsJson, String bearer) {
        try {
            String status = parseStatus(argumentsJson);
            log.debug("查询任务：status={}", status);
            String result = contentClient.listTasks(bearer, status, 1, 20);
            return ToolResult.success(result);
        } catch (FeignException e) {
            log.warn("查询任务失败：{}", argumentsJson, e);
            return ToolResult.failure("查询任务失败: " + e.getMessage());
        }
    }

    private String parseStatus(String argumentsJson) {
        try {
            if (argumentsJson == null || argumentsJson.isBlank()) {
                return null;
            }
            JsonNode args = json.readTree(argumentsJson);
            return args.path("status").asText(null);
        } catch (JsonProcessingException e) {
            log.warn("解析任务状态参数失败：{}", argumentsJson, e);
            return null;
        }
    }
}
