package com.familyhome.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.sql.Timestamp;
import java.sql.Blob;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@Import(OutboxPersistenceConfiguration.class)
@EnableScheduling
@ConditionalOnProperty(name = "family.messaging.enabled", havingValue = "true")
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    /** 每次清理批次大小，避免一条 DELETE 长时间持锁。 */
    private static final int PURGE_BATCH = 500;

    @Autowired private OutboxMapper outboxMapper;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private MessagingProperties properties;
    @Autowired private ObjectMapper mapper;

    @Scheduled(fixedDelayString = "${family.messaging.publisher-delay-ms:1000}")
    public void publishBatch() {
        List<Map<String, Object>> events = outboxMapper.pending();
        for (Map<String, Object> event : events) publish(event);
    }

    /**
     * 清理已发布的历史事件。
     *
     * <p>Outbox 表原来只增不减——发布成功后行永远留着，几个月就是几十万行，
     * 而 {@code pending()} 每次都要扫它。这里按保留期分批删除。
     */
    @Scheduled(fixedDelayString = "${family.messaging.purge-delay-ms:3600000}")
    public void purgePublished() {
        Timestamp before =
                Timestamp.from(Instant.now().minus(Duration.ofDays(properties.retentionDays())));
        int removed = 0;
        while (true) {
            int batch = outboxMapper.purgePublished(before, PURGE_BATCH);
            removed += batch;
            if (batch < PURGE_BATCH) break;
        }
        // 积压的 PENDING 说明发布链路或 broker 有问题，主动暴露出来
        int stale = outboxMapper.countStalePending(before);
        if (stale > 0)
            log.warn(
                    "outbox 存在 {} 条超过 {} 天仍未发布的事件，请检查 RabbitMQ 连接与发布日志",
                    stale,
                    properties.retentionDays());
        if (removed > 0)
            log.info("outbox 清理已发布事件 rows={} retentionDays={}", removed, properties.retentionDays());
        int failed = outboxMapper.countFailed();
        if (failed > 0)
            log.error("outbox 有 {} 条事件已达重试上限转为 FAILED，需人工处理", failed);
    }

    private void publish(Map<String, Object> event) {
        String id = String.valueOf(event.get("id"));
        // 先认领：多实例同时轮询时只有一次 UPDATE 命中，其余实例直接跳过，避免重复投递。
        if (outboxMapper.claim(id) != 1) return;
        int attempts = ((Number) event.get("attempts")).intValue();
        try {
            Map<String, Object> body = Map.of(
                    "eventId", id,
                    "aggregateType", event.get("aggregate_type"),
                    "aggregateId", event.get("aggregate_id"),
                    "eventType", event.get("event_type"),
                    "householdId", event.get("household_id"),
                    "payload", mapper.readTree(payloadJson(event.get("payload"))));
            rabbit.convertAndSend(properties.exchange(), String.valueOf(event.get("event_type")), body, message -> {
                message.getMessageProperties().setMessageId(id);
                message.getMessageProperties().setHeader("x-event-id", id);
                return message;
            });
            outboxMapper.published(id);
            log.info("outbox event published id={} type={}", id, event.get("event_type"));
        } catch (Exception ex) {
            int next = attempts + 1;
            if (next >= properties.maxAttempts()) {
                outboxMapper.markFailed(id, trim(ex.getMessage()));
                log.error(
                        "outbox event 达到重试上限，已转入 FAILED 待人工处理 id={} type={} attempts={}",
                        id, event.get("event_type"), next, ex);
                return;
            }
            Duration delay = Duration.ofSeconds(Math.min(300, 1L << Math.min(attempts, 8)));
            outboxMapper.retry(id, Timestamp.from(Instant.now().plus(delay)), trim(ex.getMessage()));
            log.warn("outbox event publish failed id={} attempt={}", id, next, ex);
        }
    }

    private String trim(String value) {
        if (value == null) return "unknown";
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private String payloadJson(Object value) throws Exception {
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        if (value instanceof Blob blob) return new String(blob.getBytes(1, (int) blob.length()), StandardCharsets.UTF_8);
        return String.valueOf(value);
    }
}
