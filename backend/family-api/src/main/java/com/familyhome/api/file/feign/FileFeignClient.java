package com.familyhome.api.file.feign;

import com.familyhome.api.file.vo.FileApiVO;
import com.familyhome.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(
        name = "family-file-svc",
        contextId = "familyFileClient",
        url = "${family.clients.file-url:}",
        fallback = FileFeignClientFallback.class)
public interface FileFeignClient {
    @GetMapping("/api/files/{id}/metadata")
    ApiResponse<FileApiVO> metadata(
            @RequestHeader("Authorization") String bearer, @PathVariable("id") String id);
}
