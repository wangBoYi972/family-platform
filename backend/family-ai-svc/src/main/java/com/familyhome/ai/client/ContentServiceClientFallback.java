package com.familyhome.ai.client;

import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * Content Service 降级工厂。
 *
 * <p>下游服务失败时返回结构化错误，而不是抛异常打断整个对话。
 *
 * @author family-platform
 */
@Component
public class ContentServiceClientFallback implements FallbackFactory<ContentServiceClient> {
    private static final Logger log = LoggerFactory.getLogger(ContentServiceClientFallback.class);

    @Override
    public ContentServiceClient create(Throwable cause) {
        return new ContentServiceClient() {
            @Override
            public String listTasks(String bearer, String status, int page, int size) {
                return handleError("查询任务", cause);
            }

            @Override
            public String listCalendar(String bearer, int page, int size) {
                return handleError("查询日程", cause);
            }

            @Override
            public String searchDynamics(String bearer, String keyword, int page, int size) {
                return handleError("搜索动态", cause);
            }

            @Override
            public String searchAlbums(String bearer, String keyword, int page, int size) {
                return handleError("搜索相册", cause);
            }

            @Override
            public String searchNotices(String bearer, String keyword, int page, int size) {
                return handleError("搜索公告", cause);
            }
        };
    }

    private String handleError(String action, Throwable cause) {
        if (cause instanceof FeignException fe) {
            log.warn("{}失败：HTTP {}", action, fe.status());
            return "{\"error\":\"" + action + "服务暂时不可用\"}";
        }
        log.warn("{}失败", action, cause);
        return "{\"error\":\"" + cause.getMessage() + "\"}";
    }
}
