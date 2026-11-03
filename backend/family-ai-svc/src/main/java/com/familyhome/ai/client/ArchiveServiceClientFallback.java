package com.familyhome.ai.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * Archive Service 降级工厂。
 *
 * @author family-platform
 */
@Component
public class ArchiveServiceClientFallback implements FallbackFactory<ArchiveServiceClient> {
    private static final Logger log = LoggerFactory.getLogger(ArchiveServiceClientFallback.class);

    @Override
    public ArchiveServiceClient create(Throwable cause) {
        return (bearer, keyword, page, size) -> {
            log.warn("搜索档案失败", cause);
            return "{\"error\":\"档案服务暂时不可用\"}";
        };
    }
}
