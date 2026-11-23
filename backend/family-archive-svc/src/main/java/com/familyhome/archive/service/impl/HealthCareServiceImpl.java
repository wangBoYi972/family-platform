package com.familyhome.archive.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyhome.archive.config.FamilyClock;
import com.familyhome.archive.dto.FollowUpVisitSaveDTO;
import com.familyhome.archive.dto.MedicationCheckDTO;
import com.familyhome.archive.dto.MedicationPlanSaveDTO;
import com.familyhome.archive.mapper.FollowUpVisitMapper;
import com.familyhome.archive.mapper.MedicationCheckMapper;
import com.familyhome.archive.mapper.MedicationPlanMapper;
import com.familyhome.archive.model.FollowUpVisit;
import com.familyhome.archive.model.MedicationCheck;
import com.familyhome.archive.model.MedicationPlan;
import com.familyhome.archive.security.ArchiveCipher;
import com.familyhome.archive.service.HealthCareService;
import com.familyhome.archive.vo.FollowUpVisitVO;
import com.familyhome.archive.vo.HealthReminderVO;
import com.familyhome.archive.vo.MedicationDoseVO;
import com.familyhome.archive.vo.MedicationPlanVO;
import com.familyhome.security.SessionPrincipal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 用药与复诊实现：复用档案服务的 AES 密钥、Flyway 库和会话家庭隔离。 */
@Service
public class HealthCareServiceImpl implements HealthCareService {
    /** 模板键与即将接入的订阅消息链路约定一致：微信没配置时链路自动跳过。 */
    private static final String TEMPLATE_KEY = "medication-reminder";

    @Autowired private MedicationPlanMapper plans;
    @Autowired private MedicationCheckMapper checks;
    @Autowired private FollowUpVisitMapper visits;
    @Autowired private ArchiveCipher cipher;
    @Autowired private ObjectMapper json;
    @Autowired private FamilyClock clock;

    private record MedicationBody(String name, String dosage, String note) {}
    private record VisitBody(String title, String hospital, String note) {}

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在或未授权");
    }

    private static String context(String table, long householdId, String id) {
        return householdId + ":" + table + ":" + id;
    }

    // 可见性与档案一致：PRIVATE 仅本人可读，管理员也不例外。
    private LambdaQueryWrapper<MedicationPlan> readablePlans(SessionPrincipal p) {
        return new LambdaQueryWrapper<MedicationPlan>()
                .eq(MedicationPlan::getHouseholdId, p.householdId())
                .and(q -> q.eq(MedicationPlan::getOwnerId, p.userId())
                        .or().eq(MedicationPlan::getVisibility, "FAMILY"));
    }

    private LambdaQueryWrapper<FollowUpVisit> readableVisits(SessionPrincipal p) {
        return new LambdaQueryWrapper<FollowUpVisit>()
                .eq(FollowUpVisit::getHouseholdId, p.householdId())
                .and(q -> q.eq(FollowUpVisit::getOwnerId, p.userId())
                        .or().eq(FollowUpVisit::getVisibility, "FAMILY"));
    }

    private MedicationPlan findPlan(SessionPrincipal p, String id) {
        var row = plans.selectOne(readablePlans(p).eq(MedicationPlan::getId, id));
        if (row == null) throw notFound();
        return row;
    }

    private FollowUpVisit findVisit(SessionPrincipal p, String id) {
        var row = visits.selectOne(readableVisits(p).eq(FollowUpVisit::getId, id));
        if (row == null) throw notFound();
        return row;
    }

    private void requireOwner(SessionPrincipal p, Long ownerId) {
        // PRIVATE 在 find 阶段已经 404；FAMILY 可读不等于可改。
        if (!Objects.equals(ownerId, p.userId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有创建者可以操作");
    }

    private MedicationBody readMedicationBody(MedicationPlan row) {
        try {
            String text = cipher.decrypt(
                    row.getEncryptedBody(), context("medication", row.getHouseholdId(), row.getId()));
            return json.readValue(text, MedicationBody.class);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.GONE, "健康内容无法解密，可能是密钥已更换");
        }
    }

    private VisitBody readVisitBody(FollowUpVisit row) {
        try {
            String text = cipher.decrypt(
                    row.getEncryptedBody(), context("follow_up", row.getHouseholdId(), row.getId()));
            return json.readValue(text, VisitBody.class);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.GONE, "健康内容无法解密，可能是密钥已更换");
        }
    }

    private String encryptBody(Object body, String table, long householdId, String id) {
        try {
            return cipher.encrypt(json.writeValueAsString(body), context(table, householdId, id));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("健康内容序列化失败", ex);
        }
    }

    private List<String> timesOf(MedicationPlan row) {
        if (row.getDoseTimes() == null || row.getDoseTimes().isBlank()) return List.of();
        return Arrays.stream(row.getDoseTimes().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).sorted().toList();
    }

    /** 去重并排序，一天最多八个时刻，避免用户输入把 VARCHAR 撑爆。 */
    private String normalizeTimes(List<String> times) {
        List<String> result = times == null ? List.of() : times.stream()
                .map(t -> t == null ? "" : t.trim())
                .filter(t -> !t.isEmpty())
                .distinct().sorted().limit(8).toList();
        if (result.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少填写一个服药时刻");
        return String.join(",", result);
    }

    private MedicationPlanVO medicationVO(MedicationPlan row) {
        MedicationBody body = readMedicationBody(row);
        return new MedicationPlanVO(
                row.getId(), row.getOwnerId(), body.name(), body.dosage(), body.note(),
                timesOf(row), row.getStartDate(), row.getEndDate(), row.getVisibility(),
                Boolean.TRUE.equals(row.getEnabled()), row.getCreatedAt());
    }

    private FollowUpVisitVO visitVO(FollowUpVisit row) {
        VisitBody body = readVisitBody(row);
        return new FollowUpVisitVO(
                row.getId(), row.getOwnerId(), body.title(), body.hospital(), body.note(),
                row.getVisitAt(), row.getRemindDays(), row.getVisibility(), row.getCreatedAt());
    }

    @Override
    public List<MedicationPlanVO> listMedications(SessionPrincipal p) {
        return plans.selectList(readablePlans(p).orderByDesc(MedicationPlan::getCreatedAt))
                .stream().map(this::medicationVO).toList();
    }

    @Override
    public MedicationPlanVO medicationDetail(SessionPrincipal p, String id) {
        return medicationVO(findPlan(p, id));
    }

    @Override
    @Transactional
    public MedicationPlanVO createMedication(SessionPrincipal p, MedicationPlanSaveDTO input) {
        var row = new MedicationPlan();
        row.setId(UUID.randomUUID().toString());
        row.setHouseholdId(p.householdId());
        row.setOwnerId(p.userId());
        row.setDoseTimes(normalizeTimes(input.times()));
        row.setStartDate(input.startDate());
        row.setEndDate(input.endDate());
        if (row.getEndDate().isBefore(row.getStartDate()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结束日不能早于开始日");
        row.setVisibility(input.visibility());
        row.setEnabled(input.enabled() == null || input.enabled());
        var now = LocalDateTime.now(clock.clock());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setEncryptedBody(encryptBody(
                new MedicationBody(input.name(), input.dosage(), input.note()),
                "medication", row.getHouseholdId(), row.getId()));
        plans.insert(row);
        return medicationVO(row);
    }

    @Override
    @Transactional
    public void updateMedication(SessionPrincipal p, String id, MedicationPlanSaveDTO input) {
        var row = findPlan(p, id);
        requireOwner(p, row.getOwnerId());
        row.setDoseTimes(normalizeTimes(input.times()));
        row.setStartDate(input.startDate());
        row.setEndDate(input.endDate());
        if (row.getEndDate().isBefore(row.getStartDate()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结束日不能早于开始日");
        row.setVisibility(input.visibility());
        row.setEnabled(input.enabled() == null || input.enabled());
        row.setUpdatedAt(LocalDateTime.now(clock.clock()));
        row.setEncryptedBody(encryptBody(
                new MedicationBody(input.name(), input.dosage(), input.note()),
                "medication", row.getHouseholdId(), row.getId()));
        plans.update(row, new LambdaUpdateWrapper<MedicationPlan>()
                .eq(MedicationPlan::getId, id)
                .eq(MedicationPlan::getHouseholdId, p.householdId())
                .eq(MedicationPlan::getOwnerId, p.userId()));
    }

    @Override
    @Transactional
    public void deleteMedication(SessionPrincipal p, String id) {
        var row = findPlan(p, id);
        requireOwner(p, row.getOwnerId());
        plans.delete(new LambdaQueryWrapper<MedicationPlan>()
                .eq(MedicationPlan::getId, id)
                .eq(MedicationPlan::getHouseholdId, p.householdId())
                .eq(MedicationPlan::getOwnerId, p.userId()));
        checks.delete(new LambdaQueryWrapper<MedicationCheck>()
                .eq(MedicationCheck::getHouseholdId, p.householdId())
                .eq(MedicationCheck::getPlanId, id));
    }

    @Override
    public List<MedicationDoseVO> todayMedications(SessionPrincipal p, LocalDate today) {
        List<MedicationPlan> rows = plans.selectList(readablePlans(p)
                .eq(MedicationPlan::getEnabled, true)
                .le(MedicationPlan::getStartDate, today)
                .ge(MedicationPlan::getEndDate, today)
                .orderByAsc(MedicationPlan::getEndDate));
        if (rows.isEmpty()) return List.of();
        List<String> ids = rows.stream().map(MedicationPlan::getId).toList();
        Set<String> done = checks.selectList(new LambdaQueryWrapper<MedicationCheck>()
                        .eq(MedicationCheck::getHouseholdId, p.householdId())
                        .eq(MedicationCheck::getCheckDate, today)
                        .in(MedicationCheck::getPlanId, ids))
                .stream().map(c -> c.getPlanId() + "|" + c.getDoseTime())
                .collect(Collectors.toSet());
        List<MedicationDoseVO> result = new ArrayList<>();
        for (MedicationPlan row : rows) {
            MedicationBody body = readMedicationBody(row);
            for (String time : timesOf(row)) {
                boolean taken = done.contains(row.getId() + "|" + time);
                MedicationCheck checked = taken ? checks.selectOne(new LambdaQueryWrapper<MedicationCheck>()
                        .eq(MedicationCheck::getHouseholdId, p.householdId())
                        .eq(MedicationCheck::getPlanId, row.getId())
                        .eq(MedicationCheck::getCheckDate, today)
                        .eq(MedicationCheck::getDoseTime, time)) : null;
                result.add(new MedicationDoseVO(row.getId(), body.name(), body.dosage(), time,
                        taken, checked == null ? null : checked.getCheckedBy()));
            }
        }
        result.sort(Comparator.comparing(MedicationDoseVO::time).thenComparing(MedicationDoseVO::planId));
        return result;
    }

    @Override
    @Transactional
    public void checkMedication(SessionPrincipal p, String id, MedicationCheckDTO input, LocalDate today) {
        var row = findPlan(p, id);
        String time = input.time() == null ? "" : input.time().trim();
        if (!timesOf(row).contains(time))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该时刻不在用药计划中");
        if (today.isBefore(row.getStartDate()) || today.isAfter(row.getEndDate()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "今天不在用药计划有效期内");

        var exists = checks.selectOne(new LambdaQueryWrapper<MedicationCheck>()
                .eq(MedicationCheck::getHouseholdId, p.householdId())
                .eq(MedicationCheck::getPlanId, id)
                .eq(MedicationCheck::getCheckDate, today)
                .eq(MedicationCheck::getDoseTime, time));
        if (exists != null) return; // 重复勾选直接返回，不产生第二行

        var check = new MedicationCheck();
        check.setHouseholdId(p.householdId());
        check.setPlanId(id);
        check.setCheckDate(today);
        check.setDoseTime(time);
        check.setCheckedBy(p.userId());
        check.setCreatedAt(LocalDateTime.now(clock.clock()));
        try {
            checks.insert(check);
        } catch (DuplicateKeyException ignored) {
            // 并发勾选由数据库唯一键兜底，幂等语义与顺序勾选一致。
        }
    }

    @Override
    public List<FollowUpVisitVO> listFollowUps(SessionPrincipal p) {
        return visits.selectList(readableVisits(p).orderByAsc(FollowUpVisit::getVisitAt))
                .stream().map(this::visitVO).toList();
    }

    @Override
    public FollowUpVisitVO followUpDetail(SessionPrincipal p, String id) {
        return visitVO(findVisit(p, id));
    }

    @Override
    @Transactional
    public FollowUpVisitVO createFollowUp(SessionPrincipal p, FollowUpVisitSaveDTO input) {
        var row = new FollowUpVisit();
        row.setId(UUID.randomUUID().toString());
        row.setHouseholdId(p.householdId());
        row.setOwnerId(p.userId());
        row.setVisitAt(input.visitAt());
        row.setRemindDays(input.remindDays() == null ? 0 : input.remindDays());
        row.setVisibility(input.visibility());
        var now = LocalDateTime.now(clock.clock());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setEncryptedBody(encryptBody(
                new VisitBody(input.title(), input.hospital(), input.note()),
                "follow_up", row.getHouseholdId(), row.getId()));
        visits.insert(row);
        return visitVO(row);
    }

    @Override
    @Transactional
    public void updateFollowUp(SessionPrincipal p, String id, FollowUpVisitSaveDTO input) {
        var row = findVisit(p, id);
        requireOwner(p, row.getOwnerId());
        row.setVisitAt(input.visitAt());
        row.setRemindDays(input.remindDays() == null ? 0 : input.remindDays());
        row.setVisibility(input.visibility());
        row.setUpdatedAt(LocalDateTime.now(clock.clock()));
        row.setEncryptedBody(encryptBody(
                new VisitBody(input.title(), input.hospital(), input.note()),
                "follow_up", row.getHouseholdId(), row.getId()));
        visits.update(row, new LambdaUpdateWrapper<FollowUpVisit>()
                .eq(FollowUpVisit::getId, id)
                .eq(FollowUpVisit::getHouseholdId, p.householdId())
                .eq(FollowUpVisit::getOwnerId, p.userId()));
    }

    @Override
    @Transactional
    public void deleteFollowUp(SessionPrincipal p, String id) {
        var row = findVisit(p, id);
        requireOwner(p, row.getOwnerId());
        visits.delete(new LambdaQueryWrapper<FollowUpVisit>()
                .eq(FollowUpVisit::getId, id)
                .eq(FollowUpVisit::getHouseholdId, p.householdId())
                .eq(FollowUpVisit::getOwnerId, p.userId()));
    }

    @Override
    public List<FollowUpVisitVO> upcomingFollowUps(SessionPrincipal p, int days, LocalDate today) {
        int window = Math.max(1, Math.min(30, days));
        LocalDateTime from = today.atStartOfDay();
        LocalDateTime to = today.plusDays(window).atStartOfDay();
        return visits.selectList(readableVisits(p)
                        .ge(FollowUpVisit::getVisitAt, from)
                        .lt(FollowUpVisit::getVisitAt, to)
                        .orderByAsc(FollowUpVisit::getVisitAt))
                .stream().map(this::visitVO).toList();
    }

    /**
     * 扫描今天的用药与复诊，产出按人聚合的提醒数据。
     * 订阅消息链路目前只有 anniversary 等模板位，这里不调用微信、不弹授权；
     * 后续链路补上 medication-reminder 模板后消费本方法即可，未配置微信时链路返回 skipped。
     */
    @Override
    public List<HealthReminderVO> scanTodayReminders(SessionPrincipal p, LocalDate today) {
        List<HealthReminderVO> result = new ArrayList<>();
        List<MedicationPlan> active = plans.selectList(new LambdaQueryWrapper<MedicationPlan>()
                .eq(MedicationPlan::getHouseholdId, p.householdId())
                .eq(MedicationPlan::getEnabled, true)
                .le(MedicationPlan::getStartDate, today)
                .ge(MedicationPlan::getEndDate, today));
        Map<Long, List<MedicationPlan>> byOwner = active.stream()
                .collect(Collectors.groupingBy(MedicationPlan::getOwnerId));
        for (var entry : new TreeMap<>(byOwner).entrySet()) {
            List<String> pending = new ArrayList<>();
            for (MedicationPlan row : entry.getValue()) {
                Set<String> done = checks.selectList(new LambdaQueryWrapper<MedicationCheck>()
                                .eq(MedicationCheck::getHouseholdId, row.getHouseholdId())
                                .eq(MedicationCheck::getPlanId, row.getId())
                                .eq(MedicationCheck::getCheckDate, today))
                        .stream().map(MedicationCheck::getDoseTime).collect(Collectors.toSet());
                try {
                    MedicationBody body = readMedicationBody(row);
                    timesOf(row).stream().filter(t -> !done.contains(t))
                            .forEach(t -> pending.add(body.name() + " " + t));
                } catch (RuntimeException ignored) {
                    // 单条健康内容解密失败不应中断整次扫描。
                }
            }
            if (pending.isEmpty()) continue;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("title", "今天有药");
            data.put("detail", String.join("、", pending));
            data.put("date", today.toString());
            result.add(new HealthReminderVO(TEMPLATE_KEY, "MEDICATION", entry.getKey(),
                    "medication:" + entry.getKey() + ":" + today, "pages/index/index", data));
        }

        LocalDateTime from = today.atStartOfDay();
        LocalDateTime to = today.plusDays(1).atStartOfDay();
        Map<Long, List<FollowUpVisit>> visitsByOwner = visits.selectList(
                        new LambdaQueryWrapper<FollowUpVisit>()
                                .eq(FollowUpVisit::getHouseholdId, p.householdId())
                                .ge(FollowUpVisit::getVisitAt, from)
                                .lt(FollowUpVisit::getVisitAt, to))
                .stream().collect(Collectors.groupingBy(FollowUpVisit::getOwnerId));
        for (var entry : new TreeMap<>(visitsByOwner).entrySet()) {
            try {
                FollowUpVisit first = entry.getValue().get(0);
                VisitBody body = readVisitBody(first);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("title", "今天复诊");
                data.put("detail", body.title() + (body.hospital() == null ? "" : " · " + body.hospital()));
                data.put("date", today.toString());
                data.put("time", first.getVisitAt().toLocalTime().toString());
                data.put("advanceDays", first.getRemindDays());
                result.add(new HealthReminderVO(TEMPLATE_KEY, "FOLLOW_UP", entry.getKey(),
                        "follow_up:" + entry.getKey() + ":" + today, "pages/index/index", data));
            } catch (RuntimeException ignored) {
                // 同上：坏数据跳过，不影响其它提醒。
            }
        }
        return result;
    }
}
