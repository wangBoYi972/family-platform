package com.familyhome.security.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求链路追踪与访问日志。
 *
 * <p>为每个请求生成或沿用 {@code X-Request-Id}（traceId），写入 MDC 供结构化日志关联；
 * 记录方法、路径、状态码与耗时；仅记录公开元数据，Authorization、Cookie 等敏感头一律不落日志。
 * 无论成功或异常都在 finally 中清理 MDC，避免线程复用导致上下文串扰。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);
    public static final String TRACE_HEADER = "X-Request-Id";
    public static final String TRACE_MDC_KEY = "traceId";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(TRACE_HEADER);
        String traceId = (incoming != null && !incoming.isBlank()) ? incoming.trim() : newId();
        if (traceId.length() > 64) traceId = traceId.substring(traceId.length() - 64);
        MDC.put(TRACE_MDC_KEY, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - start) / 1_000_000;
            if (!request.getRequestURI().startsWith("/actuator")
                    && !request.getRequestURI().startsWith("/v3/api-docs")
                    && !request.getRequestURI().startsWith("/swagger-ui"))
                log.info(
                        "http {} {} -> {} ({}ms)",
                        request.getMethod(),
                        sanitize(request.getRequestURI()),
                        response.getStatus(),
                        millis);
            MDC.remove(TRACE_MDC_KEY);
        }
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 仅保留路径文本，屏蔽可能包含查询参数或路径片段中的敏感信息。 */
    private String sanitize(String uri) {
        if (uri == null) return "";
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(0, q) : uri;
    }
}
