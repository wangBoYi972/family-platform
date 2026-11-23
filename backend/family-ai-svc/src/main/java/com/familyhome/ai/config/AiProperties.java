package com.familyhome.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 助手配置。key 一律走环境变量，绝不写进仓库；enabled=false（缺省）时接口返回 503，
 * 客户端按「能力未配置」提示，与微信订阅消息同一套待凭证套路。
 */
@ConfigurationProperties(prefix = "family.ai")
public record AiProperties(
        boolean enabled,
        String apiKey,
        String baseUrl,
        String model,
        /** 单次对话允许的工具调用轮数上限，防模型陷入循环查个不停。 */
        Integer maxToolRounds,
        /** 语音转写模型（OpenAI 兼容 chat/completions 协议，输入为 input_audio）。 */
        String asrModel,
        /** 下游服务地址（local 直连各服务端口，生产走内网地址）。 */
        String contentUrl,
        String fileUrl,
        String archiveUrl,
        String userUrl,
        /** 管理端保存的运行时设置落盘位置；缺省 .local/ai-settings.json。 */
        String configFile) {

    public AiProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        if (model == null || model.isBlank()) model = "qwen-plus";
        if (maxToolRounds == null || maxToolRounds < 1) maxToolRounds = 3;
        if (asrModel == null || asrModel.isBlank()) asrModel = "qwen3-asr-flash";
        if (configFile == null || configFile.isBlank()) configFile = ".local/ai-settings.json";
    }
}
