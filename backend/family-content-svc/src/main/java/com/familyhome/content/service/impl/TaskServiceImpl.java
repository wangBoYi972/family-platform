package com.familyhome.content.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.api.user.service.MemberCacheService;
import com.familyhome.content.converter.ContentConverter;
import com.familyhome.content.dto.TaskCreateDTO;
import com.familyhome.content.dto.TaskUpdateDTO;
import com.familyhome.content.mapper.FamilyTaskMapper;
import com.familyhome.content.model.FamilyTask;
import com.familyhome.content.service.TaskService;
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

    @Autowired
    private FamilyTaskMapper tasks;
    @Autowired
    private MemberCacheService memberCache;
    @Autowired
    private OutboxEventStore outbox;

    public List<TaskVO> list(SessionPrincipal p) {
        return tasks
                .selectList(
                        new LambdaQueryWrapper<FamilyTask>()
                                .eq(FamilyTask::getHouseholdId, p.householdId())
                                .orderByDesc(FamilyTask::getCreatedAt, FamilyTask::getId)
                                .last("LIMIT 500"))
                .stream()
                .map(ContentConverter::toVO)
                .toList();
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
