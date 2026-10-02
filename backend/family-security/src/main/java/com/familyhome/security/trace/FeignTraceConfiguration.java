package com.familyhome.security.trace;

import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 调用透传链路 traceId：把当前线程 MDC 中的 traceId 写入出站请求头，
 * 使跨服务调用可在日志中被串联到同一条请求链。无 traceId 时静默放行。
 */
@Configuration(proxyBeanMethods = false)
public class FeignTraceConfiguration {

    @Bean
    public RequestInterceptor traceIdPropagation() {
        return template -> {
            String traceId = MDC.get(TraceIdFilter.TRACE_MDC_KEY);
            if (traceId != null && !traceId.isBlank()) {
                template.header(TraceIdFilter.TRACE_HEADER, traceId);
            }
        };
    }
}
