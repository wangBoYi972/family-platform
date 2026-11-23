package com.familyhome.archive.vo;

import java.util.Map;

/**
 * 服务端提醒扫描结果，给即将接入的订阅消息链路消费。
 * 目前链路只有 task/anniversary 模板位，所以这里只产出可调用的数据，不主动发微信。
 */
public record HealthReminderVO(
        String templateKey,
        String kind,
        Long userId,
        String businessId,
        String page,
        Map<String, Object> data) {}
