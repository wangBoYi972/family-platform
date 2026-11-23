package com.familyhome.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** 服务间调用：发送微信订阅消息的入参（不做同家庭校验，合法性由调用方保证）。 */
public record WxInternalNotifyDTO(
        @NotNull Long userId, @NotBlank String templateKey, String page, Map<String, Object> data) {}
