package com.familyhome.ai.controller;

import com.familyhome.ai.dto.MinutesRequest;
import com.familyhome.ai.dto.MinutesVO;
import com.familyhome.ai.dto.TranscribeRequest;
import com.familyhome.ai.dto.TranscribeVO;
import com.familyhome.ai.service.AsrService;
import com.familyhome.ai.service.MinutesService;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通话录音 → 纪要 的两个内部接口，由媒体服务在后台任务里调用。
 *
 * <p>仍然要求登录态：媒体服务把提交录音那次请求的令牌原样带过来，
 * 转写和访问文件因此都受同一个用户的权限约束——后台任务不能变成越权通道。
 */
@RestController
@RequestMapping("/api/ai")
public class RecordingAiController {

    @Autowired
    private AsrService asr;

    @Autowired
    private MinutesService minutes;

    /** 单段录音转写。分段由客户端按 3 分钟切好，这里只处理一段。 */
    @PostMapping("/transcribe")
    public ApiResponse<TranscribeVO> transcribe(
            HttpServletRequest request, @Valid @RequestBody TranscribeRequest input) {
        SessionPrincipal.from(request);
        return ApiResponse.success(asr.transcribe(input, request.getHeader("Authorization")));
    }

    /** 把整通电话的转写稿整理成结构化纪要。 */
    @PostMapping("/minutes")
    public ApiResponse<MinutesVO> minutes(
            HttpServletRequest request, @Valid @RequestBody MinutesRequest input) {
        SessionPrincipal.from(request);
        return ApiResponse.success(minutes.generate(input));
    }
}
