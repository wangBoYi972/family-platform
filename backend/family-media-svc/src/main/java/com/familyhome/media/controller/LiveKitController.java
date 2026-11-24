package com.familyhome.media.controller;

import com.familyhome.common.api.ApiResponse;
import com.familyhome.media.config.LiveKitProperties;
import com.familyhome.media.config.MediaProperties;
import com.familyhome.media.service.LiveKitTokenService;
import com.familyhome.media.vo.LiveKitTokenVO;
import com.familyhome.media.vo.MediaConfigVO;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** SFU 相关的控制面接口。信令/状态机仍由 CallController 负责，这里只管媒体配置与入房凭证。 */
@RestController
@Validated
@RequestMapping("/api/media")
public class LiveKitController {
    @Autowired private LiveKitProperties liveKit;
    @Autowired private MediaProperties media;
    @Autowired private LiveKitTokenService tokens;

    /** 前端启动时读取一次，决定用哪套媒体栈，并据此限制选人上限。 */
    @GetMapping("/config")
    public ApiResponse<MediaConfigVO> config(HttpServletRequest request) {
        // 与其它 /api/media 接口一致：必须是已登录会话，避免未登录探测部署信息。
        SessionPrincipal.from(request);
        int max =
                liveKit.isEnabled() ? liveKit.getMaxParticipants() : media.getMaxParticipants();
        return ApiResponse.success(
                new MediaConfigVO(
                        liveKit.isEnabled(),
                        liveKit.isEnabled() ? liveKit.getPublicUrl() : null,
                        Math.max(2, max)));
    }

    /**
     * 用当前登录会话兑换入房凭证。鉴权在 LiveKitTokenService 内完成：
     * 只有本通通话的参与者（且通话未结束）才能拿到，身份取会话而非请求参数。
     */
    @PostMapping("/calls/{id}/livekit-token")
    public ApiResponse<LiveKitTokenVO> token(
            HttpServletRequest request, @PathVariable String id) {
        return ApiResponse.success(tokens.issue(SessionPrincipal.from(request), id));
    }
}
