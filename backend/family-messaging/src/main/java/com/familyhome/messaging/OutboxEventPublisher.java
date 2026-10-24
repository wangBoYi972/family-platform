package com.familyhome.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Outbox 事件发布器
 * 定期扫描 event_outbox 表,发布未发送的事件到 RabbitMQ
 */
@Slf4j
@Component
public class OutboxEventPublisher {

    @Autowired(required = false)
    private JdbcTemplate jdbc;

    @Autowired(required = false)
    private RabbitTemplate rabbit;

    @Value("${family.messaging.enabled:false}")
    private boolean messagingEnabled;

    @Value("${family.messaging.exchange:family.events}")
    private String exchange;

    /**
     * 每 5 秒扫描一次待发布事件
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    @Transactional
    public void publishPendingEvents() {
        if (!messagingEnabled || jdbc == null || rabbit == null) {
            return;
        }

        try {
            // 查询待发布事件(最多 100 条)
            List<Map<String, Object>> events = jdbc.queryForList(
                    "SELECT id, aggregate_type, aggregate_id, event_type, household_id, payload, created_at " +
                    "FROM event_outbox " +
                    "WHERE published_at IS NULL " +
                    "ORDER BY created_at ASC " +
                    "LIMIT 100"
            );

            if (events.isEmpty()) {
                return;
            }

            log.info("开始发布 {} 个待发布事件", events.size());

            int successCount = 0;
            int failCount = 0;

            for (Map<String, Object> event : events) {
                try {
                    publishEvent(event);
                    markAsPublished((Long) event.get("id"));
                    successCount++;
                } catch (Exception e) {
                    log.error("发布事件失败: {}", event, e);
                    markAsFailed((Long) event.get("id"), e.getMessage());
                    failCount++;
                }
            }

            log.info("事件发布完成: 成功 {}, 失败 {}", successCount, failCount);

        } catch (Exception e) {
            log.error("扫描待发布事件失败", e);
        }
    }

    /**
     * 发布单个事件到 RabbitMQ
     */
    private void publishEvent(Map<String, Object> event) {
        String eventType = (String) event.get("event_type");
        String aggregateType = (String) event.get("aggregate_type");

        // 路由键格式: {aggregate_type}.{event_type}
        String routingKey = aggregateType + "." + eventType.replace(".", "_");

        // 构造消息
        Map<String, Object> message = Map.of(
                "eventId", event.get("id"),
                "eventType", eventType,
                "aggregateType", aggregateType,
                "aggregateId", event.get("aggregate_id"),
                "householdId", event.get("household_id"),
                "payload", event.get("payload"),
                "createdAt", event.get("created_at")
        );

        rabbit.convertAndSend(exchange, routingKey, message);
        log.debug("事件已发布: {} -> {}", eventType, routingKey);
    }

    /**
     * 标记事件为已发布
     */
    private void markAsPublished(Long eventId) {
        jdbc.update(
                "UPDATE event_outbox SET published_at = ? WHERE id = ?",
                LocalDateTime.now(), eventId
        );
    }

    /**
     * 标记事件发布失败
     */
    private void markAsFailed(Long eventId, String error) {
        jdbc.update(
                "UPDATE event_outbox SET error_message = ?, retry_count = retry_count + 1 WHERE id = ?",
                error.length() > 500 ? error.substring(0, 500) : error,
                eventId
        );
    }

    /**
     * 清理已发布的旧事件(保留 7 天)
     */
    @Scheduled(cron = "0 0 3 * * ?") // 每天凌晨 3 点执行
    @Transactional
    public void cleanupPublishedEvents() {
        if (!messagingEnabled || jdbc == null) {
            return;
        }

        try {
            int deleted = jdbc.update(
                    "DELETE FROM event_outbox " +
                    "WHERE published_at IS NOT NULL " +
                    "AND published_at < DATE_SUB(NOW(), INTERVAL 7 DAY)"
            );

            if (deleted > 0) {
                log.info("清理已发布事件: {} 条", deleted);
            }
        } catch (Exception e) {
            log.error("清理已发布事件失败", e);
        }
    }
}
