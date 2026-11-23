package com.familyhome.user.controller;

import com.familyhome.common.api.ApiResponse;
import com.familyhome.user.dto.WxInternalNotifyDTO;
import com.familyhome.user.service.WxService;
import com.familyhome.user.vo.WxNotifyResultVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 微信订阅消息内部调用接口（服务间调用，不做同家庭校验）。
 */
@RestController
@RequestMapping("/internal/wx")
public class WxInternalController {
    @Autowired
    private WxService service;

    /**
     * 内部服务专用：发送订阅消息，不做同家庭校验。
     * 由调用方保证业务合法性（如定时任务的离线提醒）。
     */
    @PostMapping("/notify")
    public ApiResponse<WxNotifyResultVO> notifyInternal(@Valid @RequestBody WxInternalNotifyDTO req) {
        String result = service.notifyInternal(req.userId(), req.templateKey(), req.page(), req.data());
        return ApiResponse.success(new WxNotifyResultVO(result));
    }
}
