package com.familyhome.user.controller;

import com.familyhome.common.api.ApiResponse;
import com.familyhome.security.SessionPrincipal;
import com.familyhome.user.dto.WxGrantsDTO;
import com.familyhome.user.dto.WxNotifyDTO;
import com.familyhome.user.dto.WxSessionDTO;
import com.familyhome.user.service.WxService;
import com.familyhome.user.vo.WxNotifyResultVO;
import com.familyhome.user.vo.WxReportVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class WxController {
    @Autowired
    private WxService service;

    /** wx.login 的 code 换 openid 并绑到当前用户；未配置微信时 503，客户端静默处理。 */
    @PostMapping("/wx/session")
    public ApiResponse<Void> session(HttpServletRequest r, @Valid @RequestBody WxSessionDTO i) {
        service.bind(SessionPrincipal.from(r), i.code());
        return ApiResponse.success(null);
    }

    /** 客户端上报订阅授权结果：accept 落配额，reject 忽略。 */
    @PostMapping("/wx/subscriptions")
    public ApiResponse<WxReportVO> subscriptions(HttpServletRequest r, @Valid @RequestBody WxGrantsDTO i) {
        return ApiResponse.success(new WxReportVO(service.reportGrants(SessionPrincipal.from(r), i.grants())));
    }

    /**
     * 服务间调用：给目标用户发一条订阅消息（消耗其授权配额）。
     * 未配置 / 没配额 / 非同家庭都返回 skipped——调用方只管发，不该为此抛错。
     */
    @PostMapping("/wx/notify")
    public ApiResponse<WxNotifyResultVO> notify(HttpServletRequest r, @Valid @RequestBody WxNotifyDTO i) {
        return ApiResponse.success(new WxNotifyResultVO(service.notify(SessionPrincipal.from(r), i)));
    }
}
