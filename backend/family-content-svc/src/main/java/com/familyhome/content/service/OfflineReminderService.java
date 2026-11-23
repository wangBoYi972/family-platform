package com.familyhome.content.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.content.mapper.*;
import com.familyhome.content.model.*;
import com.familyhome.messaging.RedisOnlineConnectionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 离线提醒定时任务：扫描到期任务和纪念日，给离线成员发微信订阅消息。
 *
 * <p>每天固定时刻（早8点）扫描「截止日是今天且未完成」的任务，以及「今天发生」的纪念日。
 * 同一用户、同一业务对象、同一天只发一次，用发送记录表做幂等。
 *
 * <p>只推给该家庭的启用成员。任务若有负责人，只推负责人；纪念日推整个家庭。
 * 接收人当前在线则跳过。在线判断复用 im-svc 已有的按用户在线标记。
 *
 * <p>未配置微信时（family.wx.enabled=false），user-svc 返回 skipped，不消耗配额。
 */
@Slf4j
@Service
public class OfflineReminderService {

    @Autowired private FamilyTaskMapper tasks;
    @Autowired private CalendarEntryMapper calendar;
    @Autowired private ReminderSentLogMapper sentLogs;
    @Autowired private ObjectProvider<RedisOnlineConnectionRegistry> onlineRegistry;
    @Autowired private RestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;

    private static final String WX_NOTIFY_URL = "http://family-user-svc/internal/wx/notify";

    /**
     * 每天早8点扫描到期任务和纪念日，给离线成员发送提醒。
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 0 8 * * ?")
    public void sendDailyReminders() {
        LocalDate today = LocalDate.now();
        log.info("开始扫描离线提醒，日期={}", today);

        int taskCount = sendTaskReminders(today);
        int anniversaryCount = sendAnniversaryReminders(today);

        log.info("离线提醒扫描完成，任务提醒={}，纪念日提醒={}", taskCount, anniversaryCount);
    }

    /**
     * 扫描「截止日是今天且未完成」的任务，给负责人发提醒。
     * 任务若有负责人，只推负责人；没有负责人则不推。
     */
    private int sendTaskReminders(LocalDate today) {
        List<FamilyTask> dueTasks = tasks.selectList(new LambdaQueryWrapper<FamilyTask>()
            .eq(FamilyTask::getDeadline, today)
            .ne(FamilyTask::getStatus, "COMPLETED"));

        int sent = 0;
        for (FamilyTask task : dueTasks) {
            if (task.getAssigneeId() == null) continue; // 没有负责人，不推
            if (isUserOnline(task.getAssigneeId())) {
                log.debug("用户在线，跳过任务提醒 userId={} taskId={}", task.getAssigneeId(), task.getId());
                continue;
            }
            if (alreadySent(task.getAssigneeId(), "task", task.getId(), today)) {
                log.debug("今天已发送，跳过任务提醒 userId={} taskId={}", task.getAssigneeId(), task.getId());
                continue;
            }

            String result = sendTaskNotify(task);
            recordSent(task.getAssigneeId(), "task", task.getId(), today, result);
            if ("sent".equals(result)) sent++;
        }
        return sent;
    }

    /**
     * 扫描「今天发生」的纪念日，给家庭所有启用成员发提醒。
     */
    private int sendAnniversaryReminders(LocalDate today) {
        // 查询今天的纪念日：eventDate 等于今天，或 yearly=true 且月日匹配
        List<CalendarEntry> anniversaries = calendar.selectList(new LambdaQueryWrapper<CalendarEntry>()
            .and(w -> w.eq(CalendarEntry::getEventDate, today)
                .or(inner -> inner.eq(CalendarEntry::getYearly, true)
                    .apply("MONTH(event_date) = {0} AND DAY(event_date) = {1}",
                        today.getMonthValue(), today.getDayOfMonth()))));

        int sent = 0;
        for (CalendarEntry anniversary : anniversaries) {
            // 从 content entry 获取创建者，推给创建者
            if (isUserOnline(anniversary.getCreatorId())) {
                log.debug("用户在线，跳过纪念日提醒 userId={} anniversaryId={}",
                    anniversary.getCreatorId(), anniversary.getId());
                continue;
            }
            if (alreadySent(anniversary.getCreatorId(), "anniversary", anniversary.getId(), today)) {
                log.debug("今天已发送，跳过纪念日提醒 userId={} anniversaryId={}",
                    anniversary.getCreatorId(), anniversary.getId());
                continue;
            }

            String result = sendAnniversaryNotify(anniversary.getCreatorId(), anniversary);
            recordSent(anniversary.getCreatorId(), "anniversary", anniversary.getId(), today, result);
            if ("sent".equals(result)) sent++;
        }
        return sent;
    }

    /** 发送任务提醒 */
    private String sendTaskNotify(FamilyTask task) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("title", task.getTitle());
            data.put("deadline", task.getDeadline().toString());

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("userId", task.getAssigneeId());
            request.put("templateKey", "task-reminder");
            request.put("page", "pages/task/detail?id=" + task.getId());
            request.put("data", data);

            String json = objectMapper.writeValueAsString(request);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(json, headers);

            String response = restTemplate.postForObject(WX_NOTIFY_URL, entity, String.class);
            JsonNode jsonNode = objectMapper.readTree(response);
            String result = jsonNode.path("data").path("result").asText("failed");

            log.info("任务提醒发送 userId={} taskId={} result={}", task.getAssigneeId(), task.getId(), result);
            return result;
        } catch (Exception ex) {
            log.warn("任务提醒发送异常 userId={} taskId={}", task.getAssigneeId(), task.getId(), ex);
            return "failed";
        }
    }

    /** 发送纪念日提醒 */
    private String sendAnniversaryNotify(Long userId, CalendarEntry anniversary) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("title", anniversary.getTitle());
            data.put("date", anniversary.getEventDate().toString());

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("userId", userId);
            request.put("templateKey", "anniversary-reminder");
            request.put("page", "pages/calendar/detail?id=" + anniversary.getId());
            request.put("data", data);

            String json = objectMapper.writeValueAsString(request);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(json, headers);

            String response = restTemplate.postForObject(WX_NOTIFY_URL, entity, String.class);
            JsonNode jsonNode = objectMapper.readTree(response);
            String result = jsonNode.path("data").path("result").asText("failed");

            log.info("纪念日提醒发送 userId={} anniversaryId={} result={}", userId, anniversary.getId(), result);
            return result;
        } catch (Exception ex) {
            log.warn("纪念日提醒发送异常 userId={} anniversaryId={}", userId, anniversary.getId(), ex);
            return "failed";
        }
    }

    /** 判断用户是否在线 */
    private boolean isUserOnline(Long userId) {
        try {
            RedisOnlineConnectionRegistry registry = onlineRegistry.getIfAvailable();
            return registry != null && registry.isUserOnline(userId);
        } catch (Exception ex) {
            log.debug("在线状态查询失败 userId={}，默认离线", userId, ex);
            return false; // 查询失败默认离线，宁可多发也不漏发
        }
    }

    /** 检查今天是否已发送 */
    private boolean alreadySent(Long userId, String businessType, Long businessId, LocalDate sentDate) {
        ReminderSentLog log = sentLogs.selectOne(new LambdaQueryWrapper<ReminderSentLog>()
            .eq(ReminderSentLog::getUserId, userId)
            .eq(ReminderSentLog::getBusinessType, businessType)
            .eq(ReminderSentLog::getBusinessId, businessId)
            .eq(ReminderSentLog::getSentDate, sentDate)
            .last("LIMIT 1"));
        return log != null;
    }

    /** 记录发送结果 */
    private void recordSent(Long userId, String businessType, Long businessId, LocalDate sentDate, String result) {
        ReminderSentLog log = new ReminderSentLog();
        log.setUserId(userId);
        log.setBusinessType(businessType);
        log.setBusinessId(businessId);
        log.setSentDate(sentDate);
        log.setResult(result);
        log.setCreatedAt(LocalDateTime.now());
        try {
            sentLogs.insert(log);
        } catch (DuplicateKeyException ex) {
            // 并发插入，幂等键冲突，忽略
            this.log.debug("重复发送记录 userId={} type={} id={}", userId, businessType, businessId);
        }
    }
}
