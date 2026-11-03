package com.familyhome.ai.tool.definition;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * OpenAI function calling 的工具定义。
 *
 * <p>按照 OpenAI 官方格式设计，避免 Map 嵌套，提供类型安全。
 *
 * @author family-platform
 */
public record ToolDefinition(
        String type,
        FunctionSpec function
) {
    public static ToolDefinition of(String name, String description, ParametersSpec parameters) {
        return new ToolDefinition("function", new FunctionSpec(name, description, parameters));
    }

    public record FunctionSpec(
            String name,
            String description,
            ParametersSpec parameters
    ) {}

    public record ParametersSpec(
            String type,
            Map<String, PropertySpec> properties,
            List<String> required
    ) {
        public static ParametersSpec object(Map<String, PropertySpec> properties, String... required) {
            return new ParametersSpec("object", properties, List.of(required));
        }

        public static ParametersSpec noParams() {
            return new ParametersSpec("object", Map.of(), List.of());
        }
    }

    public record PropertySpec(
            String type,
            String description,
            @JsonProperty("enum") List<String> enumValues
    ) {
        public static PropertySpec string(String description) {
            return new PropertySpec("string", description, null);
        }

        public static PropertySpec stringEnum(String description, String... values) {
            return new PropertySpec("string", description, List.of(values));
        }
    }
}
