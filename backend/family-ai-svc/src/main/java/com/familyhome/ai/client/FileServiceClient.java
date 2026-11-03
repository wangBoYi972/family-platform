package com.familyhome.ai.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * File Service（文件服务）客户端。
 *
 * @author family-platform
 */
@FeignClient(
        name = "family-file-svc",
        url = "${family.clients.file-url:}",
        fallbackFactory = FileServiceClientFallback.class
)
public interface FileServiceClient {

    @GetMapping(value = "/api/files", produces = "application/json")
    String searchFiles(
            @RequestHeader("Authorization") String bearer,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "5") int size
    );
}
