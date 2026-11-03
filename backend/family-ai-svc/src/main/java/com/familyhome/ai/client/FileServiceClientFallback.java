package com.familyhome.ai.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * File Service 降级工厂。
 *
 * @author family-platform
 */
@Component
public class FileServiceClientFallback implements FallbackFactory<FileServiceClient> {
    private static final Logger log = LoggerFactory.getLogger(FileServiceClientFallback.class);

    @Override
    public FileServiceClient create(Throwable cause) {
        return (bearer, keyword, page, size) -> {
            log.warn("搜索文件失败", cause);
            return "{\"error\":\"文件服务暂时不可用\"}";
        };
    }
}
