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
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "family.messaging.enabled", havingValue = "true")
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    @Autowired private JdbcOperations jdbc;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private MessagingProperties properties;
    @Autowired private ObjectMapper mapper;

    @Scheduled(fixedDelayString = "${family.messaging.publisher-delay-ms:1000}")
    public void publishBatch() {
        List<Map<String, Object>> events = jdbc.queryForList("SELECT id, aggregate_type, aggregate_id, event_type, household_id, payload, attempts FROM event_outbox WHERE status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP ORDER BY created_at LIMIT 50");
        for (Map<String, Object> event : events) publish(event);
    }

    private void publish(Map<String, Object> event) {
        String id = String.valueOf(event.get("id"));
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
            jdbc.update("UPDATE event_outbox SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'", id);
            log.info("outbox event published id={} type={}", id, event.get("event_type"));
        } catch (Exception ex) {
            Duration delay = Duration.ofSeconds(Math.min(300, 1L << Math.min(attempts, 8)));
            jdbc.update("UPDATE event_outbox SET attempts=attempts+1, next_attempt_at=?, last_error=? WHERE id=? AND status='PENDING'", Timestamp.from(Instant.now().plus(delay)), trim(ex.getMessage()), id);
            log.warn("outbox event publish failed id={} attempt={}", id, attempts + 1, ex);
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
