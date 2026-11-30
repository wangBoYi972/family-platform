package com.familyhome.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 服务间调用：请给某个用户发一条订阅消息。
 *
 * <p>data 用语义字段（如 sender / preview），由 user-svc 按 WxProperties 里该模板的
 * fields 映射翻译成微信的 thing1 / time2 —— 调用方不该知道模板字段 ID。
 */
public record WxNotifyDTO(
        @NotNull Long userId,
        @NotBlank @Size(max = 64) String templateKey,
        @Size(max = 512) String page,
        Map<String, Object> data) {}
