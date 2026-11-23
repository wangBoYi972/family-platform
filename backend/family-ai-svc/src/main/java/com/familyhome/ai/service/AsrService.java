package com.familyhome.ai.service;

import com.familyhome.ai.config.AiConfigStore;
import com.familyhome.ai.config.AiProperties;
import com.familyhome.ai.dto.TranscribeRequest;
import com.familyhome.ai.dto.TranscribeVO;
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
import java.util.Base64;

/**
 * 通话录音转写：把私有文件服务里的音频取回来，转成 base64 data URI 交给 ASR 模型。
 *
 * <p>为什么不直接用「公网 URL」：Paraformer 那类异步接口要求文件对云端可访问，
 * 意味着家庭通话录音要暴露在一个公开地址上。这里改用 qwen3-asr-flash 的
 * OpenAI 兼容 input_audio（支持 base64），音频只在本服务内存里过一遍，不落第三方存储。
 *
 * <p>代价是单次有大小与时长限制，因此客户端会按 3 分钟分段录制、分段转写，
 * 再按顺序拼成完整稿件。
 */
@Service
public class AsrService {

    private static final Logger log = LoggerFactory.getLogger(AsrService.class);

    /** 模型侧限制 10MB；base64 会再放大 1/3，留出余量后按 6MB 原始音频控制。 */
    private static final int MAX_AUDIO_BYTES = 6 * 1024 * 1024;

    private final AiProperties properties;
    private final AiConfigStore config;
    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired
    public AsrService(AiProperties properties, AiConfigStore config, ObjectMapper json) {
        this.properties = properties;
        this.config = config;
        this.json = json;
    }

    public TranscribeVO transcribe(TranscribeRequest input, String bearer) {
        AiConfigStore.Effective settings = config.effective();
        if (!settings.usable())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 助手未配置，无法转写通话录音");

        byte[] audio = download(input.fileId(), bearer);
        if (audio.length == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "录音文件为空");
        if (audio.length > MAX_AUDIO_BYTES)
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "单段录音超过 " + (MAX_AUDIO_BYTES / 1024 / 1024) + "MB，请缩短分段时长后重试");

        String mime = input.mime() == null || input.mime().isBlank() ? detectMime(audio) : input.mime();
        String dataUri = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(audio);

        ObjectNode body = json.createObjectNode();
        body.put("model", properties.asrModel());
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        ObjectNode part = content.addObject();
        part.put("type", "input_audio");
        part.putObject("input_audio").put("data", dataUri);
        // 家庭对话里数字与单位很常见（药量、金额、日期），开启逆文本标准化更接近原话
        body.putObject("asr_options").put("enable_itn", true);

        long started = System.currentTimeMillis();
        JsonNode response = post(body, settings.apiKey());
        String text = response.path("choices").path(0).path("message").path("content").asText("");
        text = text == null ? "" : text.trim();
        log.info("通话录音转写完成 fileId={} bytes={} chars={} cost={}ms",
                input.fileId(), audio.length, text.length(), System.currentTimeMillis() - started);
        return new TranscribeVO(text, properties.asrModel(), text.isEmpty());
    }

    private JsonNode post(ObjectNode body, String apiKey) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(180))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("转写失败 status={} body={}", response.statusCode(), brief(response.body()));
                // 401/403 是密钥问题：重试一万次也不会好，必须让运维看到「去改配置」，
                // 而不是被 502+「请稍后重试」误导成网关抖动。
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    throw new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "AI 模型密钥无效或已过期，请在管理端「AI 设置」中更新密钥");
                }
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "语音转写服务返回 " + response.statusCode() + "，请稍后重试");
            }
            return json.readTree(response.body());
        } catch (IOException e) {
            log.error("转写请求失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "语音转写服务连接失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "语音转写被中断");
        }
    }

    /** 用调用方自己的令牌下载：AI 能看到的内容不会超过这个用户自己能看到的范围。 */
    private byte[] download(String fileId, String bearer) {
        String url = properties.fileUrl() + "/api/files/" + fileId + "/download";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET();
            if (bearer != null && !bearer.isBlank()) builder.header("Authorization", bearer);
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "录音文件不存在");
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有该录音文件的访问权限");
            if (response.statusCode() != 200)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "读取录音文件失败");
            return response.body();
        } catch (IOException e) {
            log.error("下载录音失败 fileId={}", fileId, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "文件服务暂不可用");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "读取录音被中断");
        }
    }

    /**
     * 按文件头判断容器格式。文件服务统一下载为 application/octet-stream，
     * 不能依赖 Content-Type；MIME 传错模型会直接判为格式不支持。
     */
    public static String detectMime(byte[] data) {
        if (startsWith(data, 0x1A, 0x45, 0xDF, 0xA3)) return "audio/webm";
        if (startsWith(data, 0x4F, 0x67, 0x67, 0x53)) return "audio/ogg";    // OggS
        if (startsWith(data, 0x52, 0x49, 0x46, 0x46)) return "audio/wav";   // RIFF
        if (startsWith(data, 0x66, 0x4C, 0x61, 0x43)) return "audio/flac";  // fLaC
        if (startsWith(data, 0x49, 0x44, 0x33)) return "audio/mpeg";        // ID3
        if (data.length > 1 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xE0) == 0xE0) return "audio/mpeg";
        return "audio/mp4";
    }

    private static boolean startsWith(byte[] data, int... head) {
        if (data.length < head.length) return false;
        for (int i = 0; i < head.length; i++) {
            if ((data[i] & 0xFF) != head[i]) return false;
        }
        return true;
    }

    private static String brief(String body) {
        if (body == null) return "";
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }
}
