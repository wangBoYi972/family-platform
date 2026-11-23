package com.familyhome.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "family.messaging")
public record MessagingProperties(
        boolean enabled,
        String serviceName,
        String exchange,
        String queue,
        Integer retentionDays,
        Integer maxAttempts) {
    public MessagingProperties {
        if (exchange == null || exchange.isBlank()) exchange = "family.events";
        if (queue == null || queue.isBlank()) queue = serviceName + ".events";
        // 已发布事件的保留天数：低于 1 视为非法，回落到 7 天，避免误配成 0 把日志删空
        if (retentionDays == null || retentionDays < 1) retentionDays = 7;
        // 投递重试上限：到顶转 FAILED，避免一条毒消息被无限重试
        if (maxAttempts == null || maxAttempts < 1) maxAttempts = 10;
    }
}
