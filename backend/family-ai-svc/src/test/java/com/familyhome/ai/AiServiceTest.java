package com.familyhome.ai;

import com.familyhome.ai.client.ArchiveServiceClient;
import com.familyhome.ai.client.ContentServiceClient;
import com.familyhome.ai.client.FileServiceClient;
import com.familyhome.ai.config.AiConfigStore;
import com.familyhome.ai.config.AiProperties;
import com.familyhome.ai.dto.ChatRequest;
import com.familyhome.ai.service.AiService;
import com.familyhome.ai.tool.ToolRegistry;
import com.familyhome.ai.tool.impl.ListCalendarTool;
import com.familyhome.ai.tool.impl.ListTasksTool;
import com.familyhome.ai.tool.impl.SearchFamilyDataTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AiService 测试：不依赖真实模型 key，用本地 HttpServer 返回 SSE，验证流式解析与工具循环。
 */
@DisplayName("AiService")
class AiServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer fake;

    @BeforeEach
    void startFake() throws Exception {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.start();
    }

    @AfterEach
    void stopFake() {
        if (fake != null) fake.stop(0);
    }

    @Test
    @DisplayName("流式输出普通回答，末尾发 done 事件")
    void streamsAnswerThenDone() {
        fake.createContext("/chat/completions", ex -> respond(ex, sse(
                delta("今天"),
                delta("天气"),
                delta("不错"),
                "[DONE]"
        )));
        List<String> events = run(service(3), "天气");
        assertThat(events).containsExactly(
                "{\"type\":\"delta\",\"text\":\"今天\"}",
                "{\"type\":\"delta\",\"text\":\"天气\"}",
                "{\"type\":\"delta\",\"text\":\"不错\"}",
                "{\"type\":\"done\"}"
        );
    }

    @Test
    @DisplayName("模型返回 tool_calls 时推送 tool 事件，附工具名")
    void emitsToolEventWhenToolCallReceived() {
        List<String> requests = new CopyOnWriteArrayList<>();
        fake.createContext("/chat/completions", ex -> {
            requests.add(readBody(ex));
            respond(ex, requests.size() == 1
                    ? toolCallStream()
                    : sse(delta("任务已清空"), "[DONE]"));
        });
        List<String> events = run(service(3), "有什么任务?");
        assertThat(events).contains("{\"type\":\"tool\",\"name\":\"list_tasks\"}");
    }

    @Test
    @DisplayName("工具轮数达上限后，不带 tools 发最后一轮")
    void stopsToolCallsAfterMaxRounds() {
        AtomicInteger round = new AtomicInteger(0);
        fake.createContext("/chat/completions", ex -> {
            int r = round.incrementAndGet();
            respond(ex, r <= 2
                    ? toolCallStream()
                    : sse(delta("我试过了"), "[DONE]"));
        });
        List<String> events = run(service(2), "查一下");
        long toolEvents = events.stream().filter(e -> e.contains("\"type\":\"tool\"")).count();
        assertThat(toolEvents).isEqualTo(2);
    }

    @Test
    @DisplayName("配置未启用时抛 503")
    void throws503WhenDisabled() {
        AiProperties properties = new AiProperties(false, null, null, null, 3,
                null, null, null, null, tempConfig());
        AiService service = new AiService(properties, new AiConfigStore(json, properties), mockRegistry());
        assertThatThrownBy(() -> run(service, "测试"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");
    }

    @Test
    @DisplayName("模型返回非流式 tool_calls 也能解析")
    void parsesNonStreamingToolCalls() {
        fake.createContext("/chat/completions", ex -> respond(ex,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"list_calendar\",\"arguments\":\"{}\"}}]}}]}"
        ));
        List<String> events = run(service(3), "有什么安排");
        assertThat(events).contains("{\"type\":\"tool\",\"name\":\"list_calendar\"}");
    }

    @Test
    @DisplayName("对话历史超长时只保留最近 20 条，再加一条系统提示")
    void truncatesHistoryToLimit() {
        List<String> bodies = new CopyOnWriteArrayList<>();
        fake.createContext("/chat/completions", ex -> {
            bodies.add(readBody(ex));
            respond(ex, sse(delta("好"), "[DONE]"));
        });
        AiService service = service(3);
        List<ChatRequest.Message> history = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            history.add(new ChatRequest.Message("user", "消息" + i));
        }
        service.chat(history, "Bearer test", e -> {});
        JsonNode body = json(bodies.get(0));
        int sent = body.path("messages").size();
        assertThat(sent).isEqualTo(21); // system + 最近 20 条
    }

    // ---- 脚手架 ----

    private AiService service(int maxToolRounds) {
        AiProperties properties = properties(true, "test-key", maxToolRounds);
        return new AiService(properties, new AiConfigStore(json, properties), mockRegistry());
    }

    private ToolRegistry mockRegistry() {
        ContentServiceClient contentClient = mock(ContentServiceClient.class);
        when(contentClient.listTasks(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn("{\"items\":[]}");
        when(contentClient.listCalendar(anyString(), anyInt(), anyInt()))
                .thenReturn("{\"items\":[]}");
        when(contentClient.searchDynamics(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn("{\"items\":[]}");
        when(contentClient.searchAlbums(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn("{\"items\":[]}");
        when(contentClient.searchNotices(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn("{\"items\":[]}");

        ArchiveServiceClient archiveClient = mock(ArchiveServiceClient.class);
        when(archiveClient.searchArchives(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn("{\"items\":[]}");

        FileServiceClient fileClient = mock(FileServiceClient.class);
        when(fileClient.searchFiles(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn("{\"items\":[]}");

        return new ToolRegistry(List.of(
                new ListTasksTool(contentClient, json),
                new ListCalendarTool(contentClient),
                new SearchFamilyDataTool(contentClient, archiveClient, fileClient, json)
        ));
    }

    private AiProperties properties(boolean enabled, String apiKey, int maxToolRounds) {
        String baseUrl = "http://127.0.0.1:" + fake.getAddress().getPort();
        return new AiProperties(enabled, apiKey, baseUrl, "qwen-plus", maxToolRounds,
                null, null, null, null, tempConfig());
    }

    private String tempConfig() {
        try {
            Path config = Files.createTempFile("ai-test-settings", ".json");
            Files.deleteIfExists(config);
            return config.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> run(AiService service, String... userTexts) {
        List<ChatRequest.Message> history = new ArrayList<>();
        for (String text : userTexts) history.add(new ChatRequest.Message("user", text));
        List<String> events = new ArrayList<>();
        service.chat(history, "Bearer user-token", events::add);
        return events;
    }

    private JsonNode json(String text) {
        try {
            return json.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sse(String... payloads) {
        StringBuilder text = new StringBuilder();
        for (String payload : payloads) text.append("data: ").append(payload).append("\n\n");
        return text.toString();
    }

    private static String delta(String text) {
        return "{\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}";
    }

    private static String toolCallStream() {
        return sse(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"list_tasks\",\"arguments\":\"\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"stat\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"us\\\":\\\"TODO\\\"}\"}}]}}]}",
                "{\"choices\":[{\"finish_reason\":\"tool_calls\"}]}",
                "[DONE]"
        );
    }

    private static void respond(HttpExchange ex, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }
}
