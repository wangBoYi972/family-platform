package com.familyhome.ai.controller;

import com.familyhome.ai.config.AiConfigStore;
import com.familyhome.ai.dto.AiHistoryMessage;
import com.familyhome.ai.dto.ChatRequest;
import com.familyhome.ai.service.AiHistoryService;
import com.familyhome.ai.service.AiService;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    @Autowired
    private AiService service;

    @Autowired
    private AiHistoryService history;

    @Autowired
    private AiConfigStore config;

    /** 当前用户自己的助手历史。未配置模型时也能读，换设备不至于一片空白。 */
    @GetMapping("/history")
    public ApiResponse<List<AiHistoryMessage>> history(HttpServletRequest request) {
        return ApiResponse.success(history.recent(SessionPrincipal.from(request)));
    }

    /**
     * 把升级前只存在本机的历史补进库。库里已有记录时原样返回，不重复写入。
     */
    @PostMapping("/history/import")
    public ApiResponse<List<AiHistoryMessage>> importHistory(HttpServletRequest request,
                                                             @Valid @RequestBody ChatRequest input) {
        return ApiResponse.success(history.importIfEmpty(SessionPrincipal.from(request), input.messages()));
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void chat(HttpServletRequest request, HttpServletResponse response,
                     @Valid @RequestBody ChatRequest input) throws IOException {
        SessionPrincipal principal = SessionPrincipal.from(request);   // 未登录直接 401，SSE 都不开
        // 配置检查必须排在拿 writer 之前：一旦 response 的流被取走，这里再抛异常，
        // 全局异常处理器就写不出 JSON 错误体（Tomcat 抛 getWriter() has already been called），
        // 前端只能看到空响应的 503，连「未配置」这个原因都拿不到。
        if (!config.effective().usable())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 助手未配置");
        // 提问先落库：模型中途断了，这句问题也还在，换台设备打开看得到。
        // 带 clientId 时同一提问只插一次，超时重发不会变成两行。
        String question = history.latestUserText(input.messages());
        if (question != null) history.remember(principal, "user", question, input.clientId());
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        PrintWriter writer = response.getWriter();
        StringBuilder reply = new StringBuilder();
        // 同步写流：一次 AI 回答 30 秒内，家庭应用并发量级下占用一个容器线程完全可接受，
        // 比 SseEmitter + 手动线程管理简单可靠（断线时 writer 抛异常，由上层捕获停止生成）。
        service.chat(input.messages(), request.getHeader("Authorization"), line -> {
            writer.write("data: " + line + "\n\n");
            writer.flush();
            history.absorb(line, reply);
        });
        try {
            history.remember(principal, "assistant", reply.toString());
        } catch (RuntimeException ex) {
            // 用户已经看到回答。落库失败只记日志，不能再往已经开始的 SSE 里抛 500。
            log.warn("AI 回答已返回，但写入历史失败: userId={}", principal.userId(), ex);
        }
    }
}
