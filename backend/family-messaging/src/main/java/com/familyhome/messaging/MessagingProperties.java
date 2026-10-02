package com.familyhome.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "family.messaging")
public record MessagingProperties(boolean enabled, String serviceName, String exchange, String queue) {
    public MessagingProperties {
        if (exchange == null || exchange.isBlank()) exchange = "family.events";
        if (queue == null || queue.isBlank()) queue = serviceName + ".events";
    }
}
