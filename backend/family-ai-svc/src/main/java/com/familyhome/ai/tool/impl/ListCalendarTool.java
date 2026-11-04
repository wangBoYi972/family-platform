package com.familyhome.ai.tool.impl;

import com.familyhome.ai.client.ContentServiceClient;
import com.familyhome.ai.tool.Tool;
import com.familyhome.ai.tool.definition.ToolDefinition;
import com.familyhome.ai.tool.result.ToolResult;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 查询日程与纪念日工具。
 *
 * @author family-platform
 */
@Component
public class ListCalendarTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(ListCalendarTool.class);
    private static final String NAME = "list_calendar";

    private final ContentServiceClient contentClient;

    @Autowired
    public ListCalendarTool(ContentServiceClient contentClient) {
        this.contentClient = contentClient;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.of(
                NAME,
                "查询本家庭日历的日程与纪念日（最多 20 条，含日期、标题与备注）。用于回答「最近有什么安排」「某个纪念日是哪天」。",
                ToolDefinition.ParametersSpec.noParams()
        );
    }

    @Override
    public ToolResult execute(String argumentsJson, String bearer) {
        try {
            log.debug("查询日程");
            String result = contentClient.listCalendar(bearer, 1, 20);
            return ToolResult.success(result);
        } catch (FeignException e) {
            log.warn("查询日程失败", e);
            return ToolResult.failure("查询日程失败: " + e.getMessage());
        }
    }
}
