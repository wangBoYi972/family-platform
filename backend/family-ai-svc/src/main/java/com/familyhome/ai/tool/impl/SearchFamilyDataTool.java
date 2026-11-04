package com.familyhome.ai.tool.impl;

import com.familyhome.ai.client.ArchiveServiceClient;
import com.familyhome.ai.client.ContentServiceClient;
import com.familyhome.ai.client.FileServiceClient;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 跨数据源搜索工具。
 *
 * <p>并发查询 7 类数据源：任务、日程、动态、相册、公告、档案、文件。
 *
 * @author family-platform
 */
@Component
public class SearchFamilyDataTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(SearchFamilyDataTool.class);
    private static final String NAME = "search_family_data";

    private final ContentServiceClient contentClient;
    private final ArchiveServiceClient archiveClient;
    private final FileServiceClient fileClient;
    private final ObjectMapper json;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public SearchFamilyDataTool(
            ContentServiceClient contentClient,
            ArchiveServiceClient archiveClient,
            FileServiceClient fileClient,
            ObjectMapper json) {
        this.contentClient = contentClient;
        this.archiveClient = archiveClient;
        this.fileClient = fileClient;
        this.json = json;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.of(
                NAME,
                "跨数据源关键字搜索：任务、日程、动态、相册、公告、档案、文件。用于「找东西」类问题，如「体检报告放在哪」「上次发的照片」。",
                ToolDefinition.ParametersSpec.object(
                        Map.of("keyword", ToolDefinition.PropertySpec.string("搜索关键字，至少 2 个字")),
                        "keyword"
                )
        );
    }

    @Override
    public ToolResult execute(String argumentsJson, String bearer) {
        try {
            String keyword = parseKeyword(argumentsJson);
            if (keyword == null || keyword.isBlank()) {
                return ToolResult.failure("缺少搜索关键字");
            }

            log.debug("跨源搜索：keyword={}", keyword);

            // 并发查询 7 个数据源
            CompletableFuture<String> tasks = CompletableFuture.supplyAsync(
                    () -> safeCall(() -> contentClient.searchDynamics(bearer, keyword, 1, 5)),
                    executor);
            CompletableFuture<String> calendar = CompletableFuture.supplyAsync(
                    () -> safeCall(() -> contentClient.listCalendar(bearer, 1, 5)),
                    executor);
            CompletableFuture<String> dynamics = CompletableFuture.supplyAsync(
                    () -> safeCall(() -> contentClient.searchDynamics(bearer, keyword, 1, 5)),
                    executor);
            CompletableFuture<String> albums = CompletableFuture.supplyAsync(
                    () -> safeCall(() -> contentClient.searchAlbums(bearer, keyword, 1, 5)),
                    executor);
            CompletableFuture<String> notices = CompletableFuture.supplyAsync(
                    () -> safeCall(() -> contentClient.searchNotices(bearer, keyword, 1, 5)),
                    executor);
            CompletableFuture<String> archives = CompletableFuture.supplyAsync(
                    () -> safeCall(() -> archiveClient.searchArchives(bearer, keyword, 1, 5)),
                    executor);
            CompletableFuture<String> files = CompletableFuture.supplyAsync(
                    () -> safeCall(() -> fileClient.searchFiles(bearer, keyword, 1, 5)),
                    executor);

            // 等待所有查询完成（超时保护由 Feign 客户端的 readTimeout 控制）
            CompletableFuture.allOf(tasks, calendar, dynamics, albums, notices, archives, files).join();

            String result = String.format(
                    "{\"tasks\":%s,\"calendar\":%s,\"dynamics\":%s,\"albums\":%s,\"notices\":%s,\"archives\":%s,\"files\":%s}",
                    tasks.get(), calendar.get(), dynamics.get(), albums.get(), notices.get(), archives.get(), files.get()
            );

            return ToolResult.success(result);
        } catch (InterruptedException | ExecutionException e) {
            log.warn("跨源搜索失败：{}", argumentsJson, e);
            return ToolResult.failure("搜索失败: " + e.getMessage());
        }
    }

    private String parseKeyword(String argumentsJson) {
        try {
            if (argumentsJson == null || argumentsJson.isBlank()) {
                return null;
            }
            JsonNode args = json.readTree(argumentsJson);
            return args.path("keyword").asText(null);
        } catch (JsonProcessingException e) {
            log.warn("解析搜索关键字失败：{}", argumentsJson, e);
            return null;
        }
    }

    private String safeCall(java.util.function.Supplier<String> call) {
        try {
            return call.get();
        } catch (FeignException e) {
            return "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }
}
