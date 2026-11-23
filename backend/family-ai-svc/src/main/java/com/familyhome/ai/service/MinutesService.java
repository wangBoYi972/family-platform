package com.familyhome.ai.service;

import com.familyhome.ai.config.AiConfigStore;
import com.familyhome.ai.config.AiProperties;
import com.familyhome.ai.dto.MinutesRequest;
import com.familyhome.ai.dto.MinutesVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 把通话转写稿整理成纪要。
 *
 * <p>这里刻意不用工具调用、不做流式：纪要是一次性产物，媒体服务要整段落库，
 * 非流式更容易判断成功与失败。温度走默认值，让模型别自由发挥——
 * 纪要最怕的就是把「好像说过」写成「已经定了」。
 */
@Service
public class MinutesService {

    private static final Logger log = LoggerFactory.getLogger(MinutesService.class);

    /** 转写稿过长时截断：家庭通话 1 小时约 1.5 万字，超过这个量多半是识别重复。 */
    private static final int MAX_TRANSCRIPT_CHARS = 20000;

    private final AiProperties properties;
    private final AiConfigStore config;
    private final ModelSelector models;
    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired
    public MinutesService(AiProperties properties, AiConfigStore config, ModelSelector models, ObjectMapper json) {
        this.properties = properties;
        this.config = config;
        this.models = models;
        this.json = json;
    }

    public MinutesVO generate(MinutesRequest input) {
        AiConfigStore.Effective settings = config.effective();
        if (!settings.usable())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 助手未配置，无法生成通话纪要");

        String transcript = input.transcript().strip();
        if (transcript.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "转写稿为空，无法生成纪要");
        if (transcript.length() > MAX_TRANSCRIPT_CHARS)
            transcript = transcript.substring(0, MAX_TRANSCRIPT_CHARS) + "\n…（转写稿过长，后文已省略）";

        ObjectNode body = json.createObjectNode();
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt());
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt(input, transcript));

        try {
            HttpResponse<String> response = null;
            String usedModel = null;
            List<String> failures = new java.util.ArrayList<>();
            boolean credential = false;
            // 和对话一样按候选顺序试：纪要是一次性产物，重试不会产生半截内容
            for (String model : models.candidates()) {
                body.put("model", model);
                HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/chat/completions"))
                        .timeout(Duration.ofSeconds(90))
                        .header("Authorization", "Bearer " + settings.apiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> attempt =
                        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (attempt.statusCode() == 200) {
                    models.markSuccess(model);
                    response = attempt;
                    usedModel = model;
                    break;
                }
                String code = UpstreamError.code(json, attempt.body());
                log.warn("生成纪要的模型 {} 失败 status={} code={} body={}",
                        model, attempt.statusCode(), code, UpstreamError.brief(attempt.body()));
                failures.add(model + " " + attempt.statusCode() + (code.isBlank() ? "" : " " + code));
                if (attempt.statusCode() == 401) {
                    credential = true;
                    break;
                }
                if (!(attempt.statusCode() == 403 || attempt.statusCode() == 404
                        || attempt.statusCode() == 429 || attempt.statusCode() >= 500)) break;
            }
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, credential
                        ? "AI 模型密钥无效或已过期，请在管理端「AI 设置」中更新密钥"
                        : "生成纪要失败：候选模型均不可用（" + String.join("；", failures) + "）");
            }
            JsonNode root = json.readTree(response.body());
            String minutes = root.path("choices").path(0).path("message").path("content").asText("").strip();
            if (minutes.isEmpty())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型没有返回纪要内容");
            return new MinutesVO(minutes, usedModel);
        } catch (IOException e) {
            log.error("生成纪要请求失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "生成纪要失败，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "生成纪要被中断");
        }
    }

    private String systemPrompt() {
        return """
                你是家庭通话纪要助手。你的读者是刚挂断电话的一家人，他们要快速想起「刚才说了什么、谁要做什么」。

                必须遵守：
                1. 只写转写稿里出现过的信息，不补充常识、不推测动机、不美化结论。
                2. 转写稿来自语音识别，可能有错字、断句、重复、同音词。按上下文修正明显的识别错误，但不要改写事实。
                3. 没谈到的部分直接省略该小节，不要写「未提及」凑结构。
                4. 用简体中文，口语内容转成书面短句，去掉「那个」「然后」这类口头语。
                5. 输出 Markdown，不要代码块包裹，不要开场白和结尾寒暄。
                """;
    }

    private String userPrompt(MinutesRequest input, String transcript) {
        List<String> participants = input.participants() == null ? List.of() : input.participants();
        String people = participants.isEmpty() ? "（未提供名单，按「家人」称呼）" : String.join("、", participants);
        String title = input.title() == null || input.title().isBlank() ? "家庭通话" : input.title();
        String duration = input.durationMs() == null || input.durationMs() <= 0
                ? "未知"
                : Math.max(1, Math.round(input.durationMs() / 60000.0)) + " 分钟";

        return """
                今天是 %s。
                通话主题：%s
                参与人：%s
                通话时长：%s

                请严格按以下结构输出：

                ## 一句话结论
                用一句话说清这通电话的结果。

                ## 聊了什么
                3-6 条要点，每条一行，写清是什么事、谁提到的。

                ## 定下来的事
                已经明确达成一致的决定。没有就省略本小节。

                ## 待办
                每条写成「谁 · 做什么 · 什么时候」。时间没提到就写「未约定」，负责人不清楚就写「待确认」。

                ## 需要留意
                分歧、风险、需要再确认的信息。没有就省略本小节。

                转写稿：
                ---
                %s
                ---
                """.formatted(LocalDate.now(), title, people, duration, transcript);
    }
}
