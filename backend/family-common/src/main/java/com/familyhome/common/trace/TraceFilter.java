package com.familyhome.common.trace;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求追踪过滤器
 *
 * <p>为每个请求生成唯一的 TraceId,并设置到 MDC 中,用于日志追踪。
 * 同时将 TraceId 返回给客户端,方便问题排查。
 *
 * <p>符合《阿里巴巴Java开发手册》日志规约:
 * - 【推荐】在日志输出时,字符串变量之前的 '%s' 应为常量
 * - 【推荐】对 trace/debug/info 级别的日志输出,必须使用条件输出形式或者使用占位符的方式
 * - 【推荐】可以使用 warn 日志级别来记录用户输入参数错误的情况
 *
 * @author Family Platform Team
 * @since 1.0.0
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter implements Filter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String TRACE_ID_KEY = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        try {
            // 尝试从请求头获取 TraceId(用于跨服务追踪)
            String traceId = httpRequest.getHeader(TRACE_ID_HEADER);

            // 如果没有则生成新的 TraceId
            if (traceId == null || traceId.isBlank()) {
                traceId = generateTraceId();
            }

            // 设置到 MDC 中,后续所有日志都会自动包含 traceId
            MDC.put(TRACE_ID_KEY, traceId);

            // 将 TraceId 返回给客户端
            httpResponse.setHeader(TRACE_ID_HEADER, traceId);

            // 继续处理请求
            chain.doFilter(request, response);

        } finally {
            // 请求结束后清理 MDC,避免内存泄漏
            MDC.clear();
        }
    }

    /**
     * 生成 TraceId
     *
     * <p>格式: UUID前12位 + 时间戳后6位
     * 示例: a1b2c3d4e5f6-123456
     */
    private String generateTraceId() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String timestamp = String.valueOf(System.currentTimeMillis());
        return uuid.substring(0, 12) + "-" + timestamp.substring(timestamp.length() - 6);
    }
}
