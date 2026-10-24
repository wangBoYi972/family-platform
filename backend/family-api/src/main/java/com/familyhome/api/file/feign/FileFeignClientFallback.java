package com.familyhome.api.file.feign;

import com.familyhome.api.file.vo.FileApiVO;
import com.familyhome.common.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * FileFeignClient 降级处理
 */
@Slf4j
@Component
public class FileFeignClientFallback implements FileFeignClient {

    @Override
    public ApiResponse<FileApiVO> metadata(String bearer, String id) {
        log.error("FileFeignClient.metadata 调用失败,文件ID: {}", id);
        return ApiResponse.failure(503, "文件服务暂时不可用");
    }
}
