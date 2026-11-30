package com.familyhome.api.user.vo;

import java.util.Map;

/** 服务间订阅消息请求：语义字段由 user-svc 翻译成微信模板字段 ID。 */
public record WxNotifyApiVO(Long userId, String templateKey, String page, Map<String, Object> data) {}
