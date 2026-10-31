package com.familyhome.content.messaging;

import com.familyhome.content.service.ContentFileMappingService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 消费文件服务的事件，维护内容里的文件引用。
 *
 * <p>此前 content 的 {@code task.*}、file 的 {@code file.*} 事件都只有 im-svc 那个
 * 「统一广播到 WebSocket」的消费者，没有任何业务侧反应——文件被删掉后，引用它的
 * 公告/动态/相册仍留着那个 id，前端只能渲染出破图。
 *
 * <p>只关心 {@code file.deleted}：上传事件不需要内容服务做什么（关联关系由内容写入时建立）。
 */
@Component
@ConditionalOnProperty(name = "family.messaging.enabled", havingValue = "true")
public class FileEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(FileEventConsumer.class);

    @Autowired private ContentFileMappingService mappings;

    @RabbitListener(queues = "${family.messaging.queue:family-content-svc.events}")
    public void consume(Map<String, Object> event) {
        String type = String.valueOf(event.get("eventType"));
        // 本服务自己的队列绑的是 "#"，会收到全站事件，不关心的一律直接放行
        if (!"file.deleted".equals(type)) return;
        String fileId = fileIdOf(event.get("payload"));
        if (fileId == null || fileId.isBlank()) {
            log.warn("file.deleted 事件缺少 fileId，跳过 eventId={}", event.get("eventId"));
            return;
        }
        try {
            // 幂等：消息重投只会再删一次（删不到就是 0 行），不需要额外的去重表
            mappings.detachFile(fileId);
        } catch (RuntimeException ex) {
            // 抛出交给容器重试；耗尽后进死信队列，不会静默丢失
            log.error("清理内容文件引用失败 fileId={}", fileId, ex);
            throw ex;
        }
    }

    /** payload 由 Jackson 反序列化，可能是 Map 也可能是 JsonNode，两种都接受。 */
    private String fileIdOf(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            Object value = map.get("fileId");
            return value == null ? null : String.valueOf(value);
        }
        if (payload instanceof com.fasterxml.jackson.databind.JsonNode node) {
            var value = node.get("fileId");
            return value == null || value.isNull() ? null : value.asText();
        }
        return null;
    }
}
