package com.familyhome.archive.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.common.api.PageQuery;
import com.familyhome.archive.converter.ArchiveConverter;
import com.familyhome.archive.dto.ArchiveSaveDTO;
import com.familyhome.archive.mapper.*;
import com.familyhome.archive.model.*;
import com.familyhome.archive.security.ArchiveCipher;
import com.familyhome.archive.service.ArchiveService;
import com.familyhome.archive.vo.*;
import com.familyhome.security.SessionPrincipal;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ArchiveServiceImpl implements ArchiveService {
    @Autowired
    private FamilyArchiveMapper archives;
    @Autowired
    private ArchiveAccessLogMapper logs;
    @Autowired
    private ArchiveCipher cipher;

    private LambdaQueryWrapper<FamilyArchive> readable(SessionPrincipal p) {
        return new LambdaQueryWrapper<FamilyArchive>()
            .eq(FamilyArchive::getHouseholdId, p.householdId())
            .and(
                q ->
                    q.eq(FamilyArchive::getOwnerId, p.userId())
                        .or()
                        .eq(FamilyArchive::getVisibility, "FAMILY"));
    }

    public List<ArchiveVO> list(SessionPrincipal p, PageQuery page, String keyword, String category) {
        LambdaQueryWrapper<FamilyArchive> w = readable(p);
        String text = keyword == null ? "" : keyword.trim();
        // 正文是密文，列表也不返回，因此检索只覆盖标题
        if (!text.isEmpty()) w.like(FamilyArchive::getTitle, text);
        if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category))
            w.eq(FamilyArchive::getCategory, category.trim().toUpperCase(java.util.Locale.ROOT));
        return archives
            .selectList(
                w.select(
                        FamilyArchive::getId,
                        FamilyArchive::getOwnerId,
                        FamilyArchive::getTitle,
                        FamilyArchive::getCategory,
                        FamilyArchive::getVisibility,
                        FamilyArchive::getCreatedAt)
                    .orderByDesc(FamilyArchive::getCreatedAt)
                    .last(page.limitClause()))
            .stream()
            .map(ArchiveConverter::toVO)
            .toList();
    }

    private FamilyArchive find(SessionPrincipal p, String id) {
        var a = archives.selectOne(readable(p).eq(FamilyArchive::getId, id));
        if (a == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "档案不存在或未授权");
        return a;
    }

    private void owner(SessionPrincipal p, FamilyArchive a) {
        if (!Objects.equals(a.getOwnerId(), p.userId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有档案创建者可以操作");
    }

    private void log(SessionPrincipal p, String id, String action) {
        var l = new ArchiveAccessLog();
        l.setHouseholdId(p.householdId());
        l.setUserId(p.userId());
        l.setArchiveId(id);
        l.setAction(action);
        l.setCreatedAt(LocalDateTime.now());
        logs.insert(l);
    }

    @Transactional
    public ArchiveDetailVO detail(SessionPrincipal p, String id) {
        var a = find(p, id);
        // 解密失败通常是密钥轮换导致历史档案不可读。这里必须转成带中文说明的业务错误，
        // 否则会冒泡成 500 且响应体没有 msg，前端只能显示「服务暂时不可用」这种无用文案。
        String body;
        try {
            body = cipher.decrypt(a.getEncryptedBody(), p.householdId() + ":" + id);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(
                HttpStatus.GONE, "该档案内容无法解密，可能是密钥已更换，请重新录入");
        }
        log(p, id, "READ");
        return ArchiveConverter.detail(a, body);
    }

    @Transactional
    public ArchiveVO create(SessionPrincipal p, ArchiveSaveDTO i) {
        var a = new FamilyArchive();
        a.setId(UUID.randomUUID().toString());
        a.setHouseholdId(p.householdId());
        a.setOwnerId(p.userId());
        a.setTitle(i.title());
        a.setCategory(i.category());
        a.setVisibility(i.visibility());
        a.setEncryptedBody(cipher.encrypt(i.body(), p.householdId() + ":" + a.getId()));
        a.setCreatedAt(LocalDateTime.now());
        archives.insert(a);
        log(p, a.getId(), "CREATE");
        return ArchiveConverter.toVO(a);
    }

    @Transactional
    public void update(SessionPrincipal p, String id, ArchiveSaveDTO i) {
        owner(p, find(p, id));
        archives.update(
            null,
            new LambdaUpdateWrapper<FamilyArchive>()
                .eq(FamilyArchive::getId, id)
                .eq(FamilyArchive::getHouseholdId, p.householdId())
                .eq(FamilyArchive::getOwnerId, p.userId())
                .set(FamilyArchive::getTitle, i.title())
                .set(FamilyArchive::getCategory, i.category())
                .set(FamilyArchive::getVisibility, i.visibility())
                .set(
                    FamilyArchive::getEncryptedBody,
                    cipher.encrypt(i.body(), p.householdId() + ":" + id)));
        log(p, id, "UPDATE");
    }

    @Transactional
    public void delete(SessionPrincipal p, String id) {
        owner(p, find(p, id));
        archives.delete(readable(p).eq(FamilyArchive::getId, id));
        log(p, id, "DELETE");
    }

    public List<ArchiveAuditVO> audit(SessionPrincipal p, String id, PageQuery page) {
        owner(p, find(p, id));
        return logs
            .selectList(
                new LambdaQueryWrapper<ArchiveAccessLog>()
                    .eq(ArchiveAccessLog::getArchiveId, id)
                    .eq(ArchiveAccessLog::getHouseholdId, p.householdId())
                    .orderByDesc(ArchiveAccessLog::getId)
                    .last(page.limitClause()))
            .stream()
            .map(ArchiveConverter::toVO)
            .toList();
    }

    /** 总览窗口与单条上限：家庭 ≤10 人、动作低频，1000 条足够，超了也是聚合准确性的可接受取舍。 */
    static final int SUMMARY_WINDOW_DAYS = 30;
    static final int SUMMARY_MAX_LOGS = 1000;
    private static final int LOG_RETENTION_DAYS = 90; // 日志保留 90 天

    /**
     * 定时清理超过 90 天的审计日志，防止表无限增长
     * 每天凌晨 2 点执行一次
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupOldLogs() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(LOG_RETENTION_DAYS);
        int deleted = logs.delete(
            new LambdaQueryWrapper<ArchiveAccessLog>()
                .lt(ArchiveAccessLog::getCreatedAt, cutoff));
        if (deleted > 0) {
            org.slf4j.LoggerFactory.getLogger(ArchiveServiceImpl.class)
                .info("清理过期审计日志: 删除 {} 条记录", deleted);
        }
    }

    public AuditSummaryVO summary(SessionPrincipal p) {
        p.requireAdmin();
        List<ArchiveAccessLog> rows = logs.selectList(
            new LambdaQueryWrapper<ArchiveAccessLog>()
                .eq(ArchiveAccessLog::getHouseholdId, p.householdId())
                .ge(ArchiveAccessLog::getCreatedAt, LocalDateTime.now().minusDays(SUMMARY_WINDOW_DAYS))
                .orderByDesc(ArchiveAccessLog::getId)
                .last("LIMIT " + SUMMARY_MAX_LOGS));
        return aggregate(rows);
    }

    /** 聚合独立成静态方法：按人/按天/按动作三组计数，组内按次数降序、次数相同按名字升序。 */
    static AuditSummaryVO aggregate(List<ArchiveAccessLog> rows) {
        Map<Long, Long> byUser = new HashMap<>();
        Map<String, Long> byDay = new HashMap<>();
        Map<String, Long> byAction = new HashMap<>();
        for (ArchiveAccessLog row : rows) {
            byUser.merge(row.getUserId(), 1L, Long::sum);
            byDay.merge(String.valueOf(row.getCreatedAt().toLocalDate()), 1L, Long::sum);
            byAction.merge(row.getAction(), 1L, Long::sum);
        }
        return new AuditSummaryVO(
            rows.size(),
            sortedUsers(byUser),
            sortedNames(byDay),
            sortedNames(byAction));
    }

    private static List<AuditSummaryVO.NameCount> sortedNames(Map<String, Long> counts) {
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .map(e -> new AuditSummaryVO.NameCount(e.getKey(), e.getValue()))
            .toList();
    }

    private static List<AuditSummaryVO.NameCount> sortedUsers(Map<Long, Long> counts) {
        return counts.entrySet().stream()
            .sorted(Map.Entry.<Long, Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .map(e -> new AuditSummaryVO.NameCount(String.valueOf(e.getKey()), e.getValue()))
            .toList();
    }
}
