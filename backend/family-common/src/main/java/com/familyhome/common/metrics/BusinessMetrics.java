package com.familyhome.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 业务指标记录器
 * 统一管理业务层面的监控指标
 */
@Slf4j
@Component
public class BusinessMetrics {

    private final MeterRegistry registry;

    @Autowired
    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录任务创建
     */
    public void recordTaskCreated(Long householdId) {
        Counter.builder("family.task.created")
                .description("任务创建次数")
                .tag("household", String.valueOf(householdId))
                .register(registry)
                .increment();
    }

    /**
     * 记录任务完成
     */
    public void recordTaskCompleted(Long householdId, long durationDays) {
        Counter.builder("family.task.completed")
                .description("任务完成次数")
                .tag("household", String.valueOf(householdId))
                .register(registry)
                .increment();

        registry.summary("family.task.duration.days")
                .record(durationDays);
    }

    /**
     * 记录任务状态变更
     */
    public void recordTaskStatusChanged(String fromStatus, String toStatus) {
        Counter.builder("family.task.status_change")
                .description("任务状态变更次数")
                .tag("from", fromStatus)
                .tag("to", toStatus)
                .register(registry)
                .increment();
    }

    /**
     * 记录用户登录
     */
    public void recordUserLogin(Long householdId, String loginType) {
        Counter.builder("family.user.login")
                .description("用户登录次数")
                .tag("household", String.valueOf(householdId))
                .tag("type", loginType)
                .register(registry)
                .increment();
    }

    /**
     * 记录文件上传
     */
    public void recordFileUploaded(Long householdId, long sizeBytes, String fileType) {
        Counter.builder("family.file.uploaded")
                .description("文件上传次数")
                .tag("household", String.valueOf(householdId))
                .tag("type", fileType)
                .register(registry)
                .increment();

        registry.summary("family.file.size.bytes")
                .record(sizeBytes);
    }

    /**
     * 记录服务调用
     */
    public void recordServiceCall(String serviceName, String method, boolean success, long durationMs) {
        Counter.builder("family.service.call")
                .description("服务调用次数")
                .tag("service", serviceName)
                .tag("method", method)
                .tag("status", success ? "success" : "failure")
                .register(registry)
                .increment();

        Timer.builder("family.service.call.duration")
                .description("服务调用耗时")
                .tag("service", serviceName)
                .tag("method", method)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录缓存命中
     */
    public void recordCacheHit(String cacheName, boolean hit) {
        Counter.builder("family.cache.access")
                .description("缓存访问次数")
                .tag("cache", cacheName)
                .tag("result", hit ? "hit" : "miss")
                .register(registry)
                .increment();
    }

    /**
     * 记录 Outbox 事件发布
     */
    public void recordEventPublished(String eventType, boolean success) {
        Counter.builder("family.event.published")
                .description("事件发布次数")
                .tag("type", eventType)
                .tag("status", success ? "success" : "failure")
                .register(registry)
                .increment();
    }

    /**
     * 记录活跃家庭数
     */
    public void recordActiveHouseholds(int count) {
        registry.gauge("family.household.active", count);
    }

    /**
     * 记录在线用户数
     */
    public void recordOnlineUsers(int count) {
        registry.gauge("family.user.online", count);
    }
}
