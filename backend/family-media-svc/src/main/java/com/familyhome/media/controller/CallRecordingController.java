package com.familyhome.media.controller;

import com.familyhome.common.api.ApiResponse;
import com.familyhome.common.api.PageQuery;
import com.familyhome.media.dto.RecordingSubmitDTO;
import com.familyhome.media.service.CallRecordingService;
import com.familyhome.media.vo.CallRecordingVO;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通话录音与 AI 纪要。
 *
 * <p>音频不走这里：客户端分段直传文件服务，本接口只登记 file_id，
 * 再在后台把转写和纪要串起来。这样 40 分钟的通话也不会占住一个 HTTP 请求。
 */
@RestController
@Validated
@RequestMapping("/api/media")
public class CallRecordingController {

    @Autowired
    private CallRecordingService recordings;

    /** 挂断后提交录音。令牌会转发给 AI 服务，用于取回音频做转写。 */
    @PostMapping("/calls/{callId}/recording")
    public ApiResponse<CallRecordingVO> submit(
            HttpServletRequest request,
            @PathVariable String callId,
            @Valid @RequestBody RecordingSubmitDTO body) {
        return ApiResponse.success(recordings.submit(
                SessionPrincipal.from(request), request.getHeader("Authorization"), callId, body));
    }

    /** 某通电话的纪要；没开启录音时返回 null，客户端显示「未生成」。 */
    @GetMapping("/calls/{callId}/recording")
    public ApiResponse<CallRecordingVO> ofCall(HttpServletRequest request, @PathVariable String callId) {
        return ApiResponse.success(recordings.ofCall(SessionPrincipal.from(request), callId));
    }

    @GetMapping("/recordings")
    public ApiResponse<List<CallRecordingVO>> list(
            HttpServletRequest request,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(
                recordings.list(SessionPrincipal.from(request), PageQuery.of(page, size, 30)));
    }

    @GetMapping("/recordings/{id}")
    public ApiResponse<CallRecordingVO> detail(HttpServletRequest request, @PathVariable String id) {
        return ApiResponse.success(recordings.detail(SessionPrincipal.from(request), id));
    }

    /** 转写失败后重新排队；录音文件还在，不需要重新录。 */
    @PostMapping("/recordings/{id}/retry")
    public ApiResponse<CallRecordingVO> retry(HttpServletRequest request, @PathVariable String id) {
        return ApiResponse.success(recordings.retry(
                SessionPrincipal.from(request), request.getHeader("Authorization"), id));
    }
}
