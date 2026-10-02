package com.familyhome.security.trace;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    @Test
    @DisplayName("无入站 traceId 时生成新 id，写回响应头，结束后清理 MDC")
    void generatesAndClears() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/members");
        var response = new MockHttpServletResponse();
        var filter = new TraceIdFilter();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getHeader(TraceIdFilter.TRACE_HEADER)).isNotBlank();
        assertThat(MDC.get(TraceIdFilter.TRACE_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("沿用入站 traceId 并透传")
    void propagatesIncoming() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/ping");
        request.addHeader(TraceIdFilter.TRACE_HEADER, "incoming-trace");
        var response = new MockHttpServletResponse();
        new TraceIdFilter().doFilter(request, response, new MockFilterChain());
        assertThat(response.getHeader(TraceIdFilter.TRACE_HEADER)).isEqualTo("incoming-trace");
    }

    @Test
    @DisplayName("超长入站 traceId 被截断到 64 字符")
    void truncatesOversize() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/ping");
        request.addHeader(TraceIdFilter.TRACE_HEADER, "x".repeat(100));
        var response = new MockHttpServletResponse();
        new TraceIdFilter().doFilter(request, response, new MockFilterChain());
        assertThat(response.getHeader(TraceIdFilter.TRACE_HEADER)).hasSize(64);
    }

    @Test
    @DisplayName("过滤器异常时 MDC 依然被清理")
    void clearsOnException() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/ping");
        var response = new MockHttpServletResponse();
        var chain =
                new MockFilterChain() {
                    @Override
                    public void doFilter(
                            jakarta.servlet.ServletRequest req,
                            jakarta.servlet.ServletResponse res) {
                        throw new IllegalStateException("boom");
                    }
                };
        assertThatThrownBy(() -> new TraceIdFilter().doFilter(request, response, chain))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(TraceIdFilter.TRACE_MDC_KEY)).isNull();
    }
}
