package com.familyhome.media.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * AI 服务里「转写 / 纪要」两个接口的客户端。
 *
 * <p>不用 Feign：全局 readTimeout 是 5 秒，而一段 3 分钟录音的转写要十几秒到几十秒。
 * 与其为一个接口改全局超时，不如在这里显式写清每个调用能等多久。
 */
@Component
public class AiRecordingClient {

    private static final Logger log = LoggerFactory.getLogger(AiRecordingClient.class);

    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${family.clients.ai-url:http://127.0.0.1:8091}")
    private String baseUrl;

    @Autowired
    public AiRecordingClient(ObjectMapper json) {
        this.json = json;
    }

    /** 转写一段录音。返回空串表示这段没有语音，不是失败。 */
    public String transcribe(String fileId, String mime, String bearer) {
        ObjectNode body = json.createObjectNode();
        body.put("fileId", fileId);
        if (mime != null && !mime.isBlank()) body.put("mime", mime);
        JsonNode data = post("/api/ai/transcribe", body, bearer, Duration.ofSeconds(180));
        return data.path("text").asText("");
    }

    /** 由完整转写稿生成纪要（Markdown）。同样带令牌：AI 服务的接口要求登录态。 */
    public String minutes(
            String title, List<String> participants, String transcript, Integer durationMs, String bearer) {
        ObjectNode body = json.createObjectNode();
        body.put("title", title);
        ArrayNode people = body.putArray("participants");
        if (participants != null) participants.forEach(people::add);
        body.put("transcript", transcript);
        if (durationMs != null) body.put("durationMs", durationMs);
        JsonNode data = post("/api/ai/minutes", body, bearer, Duration.ofSeconds(120));
        return data.path("minutes").asText("");
    }

    private JsonNode post(String path, ObjectNode body, String bearer, Duration timeout) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
            // 纪要生成不需要访问用户数据；转写要下载录音，必须带提交者的令牌
            if (bearer != null && !bearer.isBlank()) builder.header("Authorization", bearer);
            HttpResponse<String> response =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                // AI 服务的错误响应体里有明确原因（密钥失效、未配置等），
                // 只报状态码会让纪要页显示「AI 服务返回 503」，排查时毫无指向性。
                String detail = "";
                try {
                    detail = json.readTree(response.body()).path("msg").asText("");
                } catch (Exception ignored) {
                    // 响应体不是标准 ApiResponse 时忽略，退回只报状态码
                }
                throw new IllegalStateException("AI 服务返回 " + response.statusCode()
                        + (detail.isBlank() ? "" : "：" + detail));
            }
            JsonNode root = json.readTree(response.body());
            if (root.path("code").asInt(-1) != 0)
                throw new IllegalStateException(root.path("msg").asText("AI 服务处理失败"));
            return root.path("data");
        } catch (IOException e) {
            log.warn("调用 AI 服务失败 path={}", path, e);
            throw new IllegalStateException("AI 服务暂不可用");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 调用被中断");
        }
    }
}
