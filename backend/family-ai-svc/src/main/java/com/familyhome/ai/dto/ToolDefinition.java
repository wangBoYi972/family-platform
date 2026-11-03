package com.familyhome.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * OpenAI function calling 的工具定义。
 * 用类型安全的 DTO 替代 Map 嵌套，避免硬编码和魔法字符串。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(
        String type,
        FunctionSpec function
) {
    public static ToolDefinition function(String name, String description, ParameterSpec parameters) {
        return new ToolDefinition("function", new FunctionSpec(name, description, parameters));
    }

    public record FunctionSpec(
            String name,
            String description,
            ParameterSpec parameters
    ) {}

    public record ParameterSpec(
            String type,
            PropertiesSpec properties,
            List<String> required
    ) {
        public static ParameterSpec object(PropertiesSpec properties, String... required) {
            return new ParameterSpec("object", properties, List.of(required));
        }
    }

    public record PropertiesSpec(
            PropertyDef status,
            PropertyDef keyword
    ) {
        public static PropertiesSpec empty() {
            return new PropertiesSpec(null, null);
        }

        public static PropertiesSpec withStatus() {
            return new PropertiesSpec(
                    new PropertyDef("string", "按状态筛选：TODO 待办 / DOING 进行中 / DONE 已完成；不传返回全部",
                            List.of("TODO", "DOING", "DONE")),
                    null);
        }

        public static PropertiesSpec withKeyword() {
            return new PropertiesSpec(
                    null,
                    new PropertyDef("string", "搜索关键字，至少 2 个字", null));
        }
    }

    public record PropertyDef(
            String type,
            String description,
            List<String> enumValues
    ) {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public List<String> getEnum() {
            return enumValues;
        }
    }
}
