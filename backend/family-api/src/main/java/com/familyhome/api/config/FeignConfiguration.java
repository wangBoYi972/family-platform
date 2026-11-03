package com.familyhome.api.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 统一配置
 *
 * <p>功能:
 * - 自动透传 Authorization Token
 * - 自动透传 TraceId 用于分布式追踪
 * - 超时和重试策略
 *
 * @author Family Platform Team
 * @since 1.0.0
 */
@Configuration
public class FeignConfiguration {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String TRACE_ID_KEY = "traceId";

    /**
     * 自动透传 Authorization 和 TraceId 请求头
     */
    @Bean
    public RequestInterceptor authorizationInterceptor() {
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                // 透传 Authorization Token
                ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

                if (attributes != null) {
                    String authorization = attributes.getRequest().getHeader("Authorization");
                    if (authorization != null) {
                        template.header("Authorization", authorization);
                    }
                }

                // 透传 TraceId
                String traceId = MDC.get(TRACE_ID_KEY);
                if (traceId != null) {
                    template.header(TRACE_ID_HEADER, traceId);
                }
            }
        };
    }
}
