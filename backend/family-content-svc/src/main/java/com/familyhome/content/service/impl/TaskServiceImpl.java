package com.familyhome.content.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.api.user.service.MemberCacheService;
import com.familyhome.common.api.PageQuery;
import com.familyhome.content.converter.ContentConverter;
import com.familyhome.content.dto.TaskCreateDTO;
import com.familyhome.content.dto.TaskUpdateDTO;
import com.familyhome.content.mapper.FamilyTaskMapper;
import com.familyhome.content.model.FamilyTask;
import com.familyhome.content.service.TaskService;
import com.familyhome.content.vo.TaskStatsVO;
import com.familyhome.content.vo.TaskVO;
import com.familyhome.security.SessionPrincipal;
import com.familyhome.messaging.OutboxEventStore;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TaskServiceImpl implements TaskService {
    /** 相邻状态可双向流转（开始/撤销/完成/重做）；管理员额外可把任意状态重置回待开始。 */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS =
            Map.of(
                    "TODO", Set.of("IN_PROGRESS"),
                    "IN_PROGRESS", Set.of("TODO", "COMPLETED"),
                    "COMPLETED", Set.of("IN_PROGRESS"));

    /** 列表筛选允许的状态；OVERDUE 为派生条件，不落库。 */
    private static final Set<String> ALLOWED_FILTER_STATUS =
            Set.of("TODO", "IN_PROGRESS", "COMPLETED", "OVERDUE");

    @Autowired
    private FamilyTaskMapper tasks;
    @Autowired
    private MemberCacheService memberCache;
    @Autowired
    private OutboxEventStore outbox;

    /**
     * 三条 COUNT 各走一次索引范围扫描，比把全量任务捞回内存再统计便宜得多；
     * 逾期数复用列表筛选里同一个派生条件，避免「筛选说 3 条、指标说 5 条」这种对不上的情况。
     */
    public TaskStatsVO stats(SessionPrincipal p) {
        long total = tasks.selectCount(new LambdaQueryWrapper<FamilyTask>().eq(FamilyTask::getHouseholdId, p.householdId()));
        long completed = tasks.selectCount(scoped(p).eq(FamilyTask::getStatus, "COMPLETED"));
        long overdue = tasks.selectCount(overdueCondition(scoped(p)));
        return new TaskStatsVO(total, total - completed, completed, overdue);
    }

    private LambdaQueryWrapper<FamilyTask> scoped(SessionPrincipal p) {
        return new LambdaQueryWrapper<FamilyTask>().eq(FamilyTask::getHouseholdId, p.householdId());
    }

    /** 「已逾期」= 未完成且截止日早于今天；列表筛选与汇总指标共用这一处定义。 */
    private LambdaQueryWrapper<FamilyTask> overdueCondition(LambdaQueryWrapper<FamilyTask> w) {
        return w.ne(FamilyTask::getStatus, "COMPLETED")
                .isNotNull(FamilyTask::getDeadline)
                .lt(FamilyTask::getDeadline, java.time.LocalDate.now());
    }

    public List<TaskVO> list(SessionPrincipal p, PageQuery page, String keyword, String status) {
        LambdaQueryWrapper<FamilyTask> w =
                new LambdaQueryWrapper<FamilyTask>().eq(FamilyTask::getHouseholdId, p.householdId());
        String text = keyword == null ? "" : keyword.trim();
        if (!text.isEmpty())
            // 括号不能省：不加 and() 会让「家庭 + 标题」和「负责人」变成 OR，把别的家庭的任务带出来
            w.and(
                    x ->
                            x.like(FamilyTask::getTitle, text)
                                    .or()
                                    .like(FamilyTask::getAssigneeName, text));
        switch (normalizeStatus(status)) {
            case "OVERDUE" -> overdueCondition(w);
            case "ALL" -> {}
            default -> w.eq(FamilyTask::getStatus, normalizeStatus(status));
        }
        return tasks
                .selectList(w.orderByDesc(FamilyTask::getCreatedAt, FamilyTask::getId).last(page.limitClause()))
                .stream()
                .map(ContentConverter::toVO)
                .toList();
    }

    /**
     * 归一化筛选状态。
     *
     * <p>「已逾期」不是存储状态，而是「未完成且截止日已过」的派生条件，因此在 SQL 里展开。
     * 未知取值直接拒绝，避免拼错参数时静默返回全量、让人以为筛选没生效。
     */
    static String normalizeStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) return "ALL";
        String upper = status.trim().toUpperCase(Locale.ROOT);
        if (ALLOWED_FILTER_STATUS.contains(upper)) return upper;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的任务状态筛选: " + status);
    }

    @Transactional
    public TaskVO create(SessionPrincipal p, String bearer, TaskCreateDTO i) {
        String name = null;
        if (i.assigneeId() != null) {
            // 使用缓存服务获取成员信息,减少远程调用
            name = memberCache.getMemberById(bearer, p.householdId(), i.assigneeId()).displayName();
        }
        var t = new FamilyTask();
        t.setHouseholdId(p.householdId());
        t.setCreatorId(p.userId());
        t.setTitle(i.title().trim());
        t.setAssigneeId(i.assigneeId());
        t.setAssigneeName(name);
        t.setDeadline(i.deadline());
        t.setStatus("TODO");
        t.setCreatedAt(LocalDateTime.now());
        tasks.insert(t);
        outbox.append("task", String.valueOf(t.getId()), "task.created", p.householdId(),
                Map.of("taskId", t.getId(), "title", t.getTitle(), "status", t.getStatus()));
        return ContentConverter.toVO(t);
    }

    @Transactional
    public TaskVO update(SessionPrincipal p, String bearer, long id, TaskUpdateDTO i) {
        var t = own(p, id);
        String title = i.title().trim();
        String name = null;
        if (i.assigneeId() != null) {
            // 使用缓存服务获取成员信息
            name = memberCache.getMemberById(bearer, p.householdId(), i.assigneeId()).displayName();
        }
        t.setTitle(title);
        t.setAssigneeId(i.assigneeId());
        t.setAssigneeName(name);
        t.setDeadline(i.deadline());
        if (tasks.updateById(t) != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务已更新，请刷新");
        outbox.append("task", String.valueOf(id), "task.updated", p.householdId(),
                Map.of("taskId", id, "title", title));
        return ContentConverter.toVO(t);
    }

    private FamilyTask own(SessionPrincipal p, long id) {
        var t =
                tasks.selectOne(
                        new LambdaQueryWrapper<FamilyTask>()
                                .eq(FamilyTask::getId, id)
                                .eq(FamilyTask::getHouseholdId, p.householdId()));
        if (t == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
        if (!p.admin()
                && !Objects.equals(t.getCreatorId(), p.userId())
                && !Objects.equals(t.getAssigneeId(), p.userId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅创建者或负责人可操作");
        return t;
    }

    @Transactional
    public void status(SessionPrincipal p, long id, String status) {
        String old = own(p, id).getStatus();
        if (old.equals(status)) return;
        boolean allowed =
                ALLOWED_TRANSITIONS.getOrDefault(old, Set.of()).contains(status)
                        || (p.admin() && status.equals("TODO"));
        if (!allowed) throw new ResponseStatusException(HttpStatus.CONFLICT, "不允许该任务状态变更");
        if (tasks.update(
                        null,
                        new LambdaUpdateWrapper<FamilyTask>()
                                .eq(FamilyTask::getId, id)
                                .eq(FamilyTask::getHouseholdId, p.householdId())
                                .eq(FamilyTask::getStatus, old)
                                .set(FamilyTask::getStatus, status))
                != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务已更新，请刷新");
        outbox.append("task", String.valueOf(id), "task.status-changed", p.householdId(),
                Map.of("taskId", id, "oldStatus", old, "status", status));
    }

    @Transactional
    public void delete(SessionPrincipal p, long id) {
        own(p, id);
        tasks.delete(
                new LambdaQueryWrapper<FamilyTask>()
                        .eq(FamilyTask::getId, id)
                        .eq(FamilyTask::getHouseholdId, p.householdId()));
    }
}
