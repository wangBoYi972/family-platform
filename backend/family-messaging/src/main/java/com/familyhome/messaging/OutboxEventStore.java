package com.familyhome.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Outbox 写入必须与业务事务使用同一个数据源和事务边界。 */
@Component
public class OutboxEventStore {
    @Autowired private JdbcOperations jdbc;
    @Autowired private ObjectMapper mapper;

    public String append(String aggregateType, String aggregateId, String eventType, long householdId, Map<String, ?> payload) {
        try {
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO event_outbox (id, aggregate_type, aggregate_id, event_type, household_id, payload, status, attempts, next_attempt_at, created_at) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP, ?)",
                    id, aggregateType, aggregateId, eventType, householdId, mapper.writeValueAsString(payload), OffsetDateTime.now());
            return id;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("事件负载无法序列化", ex);
        }
    }
}
