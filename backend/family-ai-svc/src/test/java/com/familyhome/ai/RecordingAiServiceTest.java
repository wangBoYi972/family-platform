package com.familyhome.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familyhome.ai.config.AiConfigStore;
import com.familyhome.ai.config.AiProperties;
import com.familyhome.ai.dto.MinutesRequest;
import com.familyhome.ai.dto.TranscribeRequest;
import com.familyhome.ai.service.AsrService;
import com.familyhome.ai.service.MinutesService;
import com.familyhome.ai.service.ModelSelector;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 转写与纪要的边界行为。
 *
 * <p>真正的模型调用在 {@code AiEndpointTest} 那种本地假服务器里验证成本高、收益低；
 * 这里锁住两件容易悄悄坏掉的事：容器格式嗅探（错了模型直接判格式不支持）
 * 和「未配置时不装作能转写」。
 */
class RecordingAiServiceTest {

    private final ObjectMapper json = new ObjectMapper();

    private AiProperties properties() {
        try {
            Path config = Files.createTempFile("ai-recording-test", ".json");
            Files.deleteIfExists(config);
            return new AiProperties(
                    false, null, "http://127.0.0.1:1", "qwen-plus", null, 3, "qwen3-asr-flash",
                    null, "http://127.0.0.1:1", null, null, config.toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AiConfigStore store(AiProperties properties) {
        AiConfigStore store = new AiConfigStore(json, properties);
        store.init();
        return store;
    }

    @Test
    @DisplayName("按文件头识别容器格式：下载接口一律 octet-stream，靠扩展名会判错")
    void detectsContainerByMagicBytes() {
        assertThat(AsrService.detectMime(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0}))
                .isEqualTo("audio/webm");
        assertThat(AsrService.detectMime("OggS....".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("audio/ogg");
        assertThat(AsrService.detectMime("RIFF....WAVE".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("audio/wav");
        assertThat(AsrService.detectMime("fLaC....".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("audio/flac");
        assertThat(AsrService.detectMime("ID3....".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("audio/mpeg");
        assertThat(AsrService.detectMime(new byte[] {(byte) 0xFF, (byte) 0xFB, 0x10, 0x00}))
                .isEqualTo("audio/mpeg");
        // 认不出来的容器按 m4a 兜底：iOS 录出来就是这格式
        assertThat(AsrService.detectMime(new byte[] {0x00, 0x00, 0x00, 0x20, 0x66, 0x74}))
                .isEqualTo("audio/mp4");
    }

    @Test
    @DisplayName("AI 未配置时转写直接 503，不去下载文件也不假装成功")
    void transcribeRequiresConfiguration() {
        AiProperties properties = properties();
        AsrService service = new AsrService(properties, store(properties), json);
        assertThatThrownBy(() -> service.transcribe(new TranscribeRequest("file-1"), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("AI 未配置时生成纪要直接 503")
    void minutesRequireConfiguration() {
        AiProperties properties = properties();
        MinutesService service = new MinutesService(properties, store(properties),
                new ModelSelector(properties), json);
        MinutesRequest request = new MinutesRequest("周末安排", List.of("小明"), "周六上午去医院", 90_000);
        assertThatThrownBy(() -> service.generate(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
