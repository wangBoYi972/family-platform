package com.familyhome.ai.controller;

import com.familyhome.ai.config.AiConfigStore;
import com.familyhome.ai.dto.ChatRequest;
import com.familyhome.ai.service.AiService;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * AI 家庭助手对话端点。响应是 SSE 流：回答逐段推送（打字机效果），
 * 模型调用工具查询家庭数据时先推 tool 事件，前端显示「正在查询…」。
 *
 * 工具查询以用户自己的 token 转发，权限由被调用方校验——AI 看到的
 * 永远不超过这个用户自己能看到的范围。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    @Autowired
    private AiService service;

    @Autowired
    private AiConfigStore config;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void chat(HttpServletRequest request, HttpServletResponse response,
                     @Valid @RequestBody ChatRequest input) throws IOException {
        SessionPrincipal.from(request);   // 未登录直接 401，SSE 都不开
        // 配置检查必须排在拿 writer 之前：一旦 response 的流被取走，这里再抛异常，
        // 全局异常处理器就写不出 JSON 错误体（Tomcat 抛 getWriter() has already been called），
        // 前端只能看到空响应的 503，连「未配置」这个原因都拿不到。
        if (!config.effective().usable())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 助手未配置");
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        PrintWriter writer = response.getWriter();
        // 同步写流：一次 AI 回答 30 秒内，家庭应用并发量级下占用一个容器线程完全可接受，
        // 比 SseEmitter + 手动线程管理简单可靠（断线时 writer 抛异常，由上层捕获停止生成）。
        service.chat(input.messages(), request.getHeader("Authorization"), line -> {
            writer.write("data: " + line + "\n\n");
            writer.flush();
        });
    }
}
