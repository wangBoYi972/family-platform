package com.familyhome.media.controller;

import com.familyhome.common.api.ApiResponse;
import com.familyhome.media.dto.CallStartDTO;
import com.familyhome.media.dto.CallStateDTO;
import com.familyhome.media.service.CallService;
import com.familyhome.media.service.IceServerService;
import com.familyhome.media.vo.CallVO;
import com.familyhome.media.vo.IceServerVO;
import com.familyhome.media.vo.WsTicketVO;
import com.familyhome.media.websocket.CallSocket;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 通话控制面（REST）。媒体面走 WebSocket 信令 + WebRTC P2P，不经过本服务。
 *
 * <p>所有接口都从已校验的会话中取家庭归属，不信任客户端传入的 householdId。
 */
@RestController
@Validated
@RequestMapping("/api/media")
public class CallController {
    @Autowired private CallService calls;
    @Autowired private IceServerService iceServers;
    @Autowired private CallSocket socket;

    @PostMapping("/calls")
    public ApiResponse<CallVO> start(
            HttpServletRequest request, @Valid @RequestBody CallStartDTO body) {
        return ApiResponse.success(
                calls.start(
                        SessionPrincipal.from(request),
                        request.getHeader("Authorization"),
                        body));
    }

    @GetMapping("/calls/active")
    public ApiResponse<CallVO> active(HttpServletRequest request) {
        return ApiResponse.success(calls.active(SessionPrincipal.from(request)));
    }

    @GetMapping("/calls/history")
    public ApiResponse<List<CallVO>> history(HttpServletRequest request) {
        return ApiResponse.success(calls.history(SessionPrincipal.from(request)));
    }

    @GetMapping("/calls/{id}")
    public ApiResponse<CallVO> detail(HttpServletRequest request, @PathVariable String id) {
        return ApiResponse.success(calls.detail(SessionPrincipal.from(request), id));
    }

    @PostMapping("/calls/{id}/answer")
    public ApiResponse<CallVO> answer(HttpServletRequest request, @PathVariable String id) {
        return ApiResponse.success(calls.answer(SessionPrincipal.from(request), id));
    }

    @PostMapping("/calls/{id}/reject")
    public ApiResponse<Void> reject(HttpServletRequest request, @PathVariable String id) {
        calls.reject(SessionPrincipal.from(request), id);
        return ApiResponse.success(null);
    }

    @PostMapping("/calls/{id}/leave")
    public ApiResponse<Void> leave(HttpServletRequest request, @PathVariable String id) {
        calls.leave(SessionPrincipal.from(request), id);
        return ApiResponse.success(null);
    }

    @PostMapping("/calls/{id}/end")
    public ApiResponse<Void> end(HttpServletRequest request, @PathVariable String id) {
        calls.end(SessionPrincipal.from(request), id);
        return ApiResponse.success(null);
    }

    @PostMapping("/calls/{id}/state")
    public ApiResponse<Void> state(
            HttpServletRequest request, @PathVariable String id, @RequestBody CallStateDTO body) {
        calls.updateMediaState(SessionPrincipal.from(request), id, body.mic(), body.cam());
        return ApiResponse.success(null);
    }

    @GetMapping("/ice-servers")
    public ApiResponse<List<IceServerVO>> ice(HttpServletRequest request) {
        return ApiResponse.success(calls.iceServers(SessionPrincipal.from(request)));
    }

    /** 用 JWT 换取一次性 WebSocket 票据；票据 30 秒有效、用后即焚。 */
    @PostMapping("/ws-ticket")
    public ApiResponse<WsTicketVO> ticket(HttpServletRequest request) {
        String ticket = socket.issueTicket(SessionPrincipal.from(request));
        return ApiResponse.success(new WsTicketVO(ticket, 30));
    }
}
