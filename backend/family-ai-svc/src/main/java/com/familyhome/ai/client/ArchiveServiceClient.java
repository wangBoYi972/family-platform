package com.familyhome.ai.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Archive Service（档案服务）客户端。
 *
 * @author family-platform
 */
@FeignClient(
        name = "family-archive-svc",
        url = "${family.clients.archive-url:}",
        fallbackFactory = ArchiveServiceClientFallback.class
)
public interface ArchiveServiceClient {

    @GetMapping(value = "/api/archives", produces = "application/json")
    String searchArchives(
            @RequestHeader("Authorization") String bearer,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "5") int size
    );
}
