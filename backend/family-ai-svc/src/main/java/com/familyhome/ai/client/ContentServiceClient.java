package com.familyhome.ai.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Content Service（内容服务）客户端。
 *
 * <p>提供任务、日程、动态、相册、公告的查询接口。
 *
 * @author family-platform
 */
@FeignClient(
        name = "family-content-svc",
        url = "${family.clients.content-url:}",
        fallbackFactory = ContentServiceClientFallback.class
)
public interface ContentServiceClient {

    /**
     * 查询任务列表。
     *
     * @param bearer 用户 token
     * @param status 状态筛选（可选）：TODO / DOING / DONE
     * @param page 页码
     * @param size 每页数量
     * @return 下游返回的原始 JSON 字符串
     */
    @GetMapping(value = "/api/tasks", produces = "application/json")
    String listTasks(
            @RequestHeader("Authorization") String bearer,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    );

    /**
     * 按关键字搜索任务。
     */
    @GetMapping(value = "/api/tasks", produces = "application/json")
    String searchTasks(
            @RequestHeader("Authorization") String bearer,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "5") int size
    );

    /**
     * 查询日程和纪念日。
     */
    @GetMapping(value = "/api/calendar", produces = "application/json")
    String listCalendar(
            @RequestHeader("Authorization") String bearer,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    );

    /**
     * 按关键字搜索日程。
     */
    @GetMapping(value = "/api/calendar", produces = "application/json")
    String searchCalendar(
            @RequestHeader("Authorization") String bearer,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "5") int size
    );

    /**
     * 搜索动态。
     */
    @GetMapping(value = "/api/dynamics", produces = "application/json")
    String searchDynamics(
            @RequestHeader("Authorization") String bearer,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "5") int size
    );

    /**
     * 搜索相册。
     */
    @GetMapping(value = "/api/albums", produces = "application/json")
    String searchAlbums(
            @RequestHeader("Authorization") String bearer,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "5") int size
    );

    /**
     * 搜索公告。
     */
    @GetMapping(value = "/api/notices", produces = "application/json")
    String searchNotices(
            @RequestHeader("Authorization") String bearer,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "5") int size
    );
}
