package com.familyhome.archive;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.archive.dto.FollowUpVisitSaveDTO;
import com.familyhome.archive.dto.MedicationCheckDTO;
import com.familyhome.archive.dto.MedicationPlanSaveDTO;
import com.familyhome.archive.mapper.MedicationCheckMapper;
import com.familyhome.archive.mapper.MedicationPlanMapper;
import com.familyhome.archive.mapper.FollowUpVisitMapper;
import com.familyhome.archive.model.MedicationCheck;
import com.familyhome.archive.service.HealthCareService;
import com.familyhome.security.SessionPrincipal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:health-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.flyway.enabled=true",
            "spring.cloud.nacos.discovery.enabled=false"
        })
@ActiveProfiles("local")
class HealthCareIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("FAMILY_ARCHIVE_KEY", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired HealthCareService service;
    @Autowired MedicationPlanMapper plans;
    @Autowired MedicationCheckMapper checks;
    @Autowired FollowUpVisitMapper visits;

    private static final ZoneId FAMILY_ZONE = ZoneId.of("Asia/Shanghai");

    private static MedicationPlanSaveDTO plan(String name, String visibility, LocalDate from, LocalDate to) {
        return new MedicationPlanSaveDTO(name, "1 片", "饭后服用", List.of("08:00", "20:00"), from, to, visibility, true);
    }

    private static void assertStatus(Throwable thrown, int status) {
        assertThat(thrown).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    @DisplayName("本人可见的计划与复诊对其他成员和管理员都是 404，健康正文以密文入库")
    void privateRecordsAreHidden() {
        var owner = new SessionPrincipal(10, 100, "MEMBER", "test", "family");
        var mate = new SessionPrincipal(11, 100, "MEMBER", "test", "family");
        var admin = new SessionPrincipal(12, 100, "ADMIN", "test", "family");
        LocalDate today = LocalDate.now(FAMILY_ZONE);
        String name = "私密药-" + UUID.randomUUID().toString().substring(0, 6);
        String title = "私密复诊-" + UUID.randomUUID().toString().substring(0, 6);

        var plan = service.createMedication(owner, plan(name, "PRIVATE", today, today.plusDays(3)));
        var visit = service.createFollowUp(owner,
                new FollowUpVisitSaveDTO(title, "中心医院", "复查血脂", today.atTime(9, 0), 1, "PRIVATE"));

        assertThat(plans.selectById(plan.id()).getEncryptedBody()).doesNotContain(name, "饭后服用");
        assertThat(visits.selectById(visit.id()).getEncryptedBody()).doesNotContain(title, "中心医院");
        assertThat(plan.name()).isEqualTo(name);
        assertStatus(catchThrowable(() -> service.medicationDetail(mate, plan.id())), 404);
        assertStatus(catchThrowable(() -> service.medicationDetail(admin, plan.id())), 404);
        assertStatus(catchThrowable(() -> service.followUpDetail(mate, visit.id())), 404);
        assertStatus(catchThrowable(() -> service.followUpDetail(admin, visit.id())), 404);
        assertStatus(catchThrowable(() -> service.updateMedication(mate, plan.id(),
                plan(name, "PRIVATE", today, today))), 404);
        assertThat(service.listMedications(mate)).extracting(v -> v.id()).doesNotContain(plan.id());
        assertThat(service.listFollowUps(admin)).extracting(v -> v.id()).doesNotContain(visit.id());
    }

    @Test
    @DisplayName("过了结束日不再出现在今天，同一时刻重复勾选不产生第二行")
    void todayAndIdempotentCheck() {
        var owner = new SessionPrincipal(20, 200, "MEMBER", "test", "family");
        LocalDate today = LocalDate.now(FAMILY_ZONE);
        var active = service.createMedication(owner,
                plan("在服药-" + UUID.randomUUID().toString().substring(0, 6), "FAMILY", today.minusDays(1), today));
        var ended = service.createMedication(owner,
                plan("已停药-" + UUID.randomUUID().toString().substring(0, 6), "FAMILY", today.minusDays(10), today.minusDays(1)));

        var todayDoses = service.todayMedications(owner, today);
        assertThat(todayDoses).extracting(v -> v.planId()).contains(active.id()).doesNotContain(ended.id());
        assertThat(todayDoses).extracting(v -> v.time()).containsExactlyInAnyOrder("08:00", "20:00");

        service.checkMedication(owner, active.id(), new MedicationCheckDTO("08:00"), today);
        service.checkMedication(owner, active.id(), new MedicationCheckDTO("08:00"), today);
        long rows = checks.selectCount(new LambdaQueryWrapper<MedicationCheck>()
                .eq(MedicationCheck::getHouseholdId, 200L)
                .eq(MedicationCheck::getPlanId, active.id())
                .eq(MedicationCheck::getCheckDate, today)
                .eq(MedicationCheck::getDoseTime, "08:00"));
        assertThat(rows).isEqualTo(1);
        assertThat(service.todayMedications(owner, today))
                .filteredOn(v -> v.planId().equals(active.id()) && v.time().equals("08:00"))
                .singleElement().satisfies(v -> assertThat(v.taken()).isTrue());
    }

    @Test
    @DisplayName("复诊今天按家庭时区判定，提醒扫描只覆盖会话家庭")
    void followUpTodayAndReminderScan() {
        var owner = new SessionPrincipal(30, 300, "MEMBER", "test", "family");
        var otherHousehold = new SessionPrincipal(99, 999, "MEMBER", "test", "family");
        LocalDate today = LocalDate.now(FAMILY_ZONE);
        service.createMedication(owner, plan("今日药", "FAMILY", today, today));
        service.createMedication(otherHousehold, plan("别家药", "FAMILY", today, today));
        var todayVisit = service.createFollowUp(owner,
                new FollowUpVisitSaveDTO("今天复诊", "社区医院", "", today.atTime(10, 30), 2, "FAMILY"));
        service.createFollowUp(owner,
                new FollowUpVisitSaveDTO("以后复诊", "社区医院", "", today.plusDays(35).atTime(10, 30), 0, "FAMILY"));

        assertThat(service.upcomingFollowUps(owner, 30, today))
                .extracting(v -> v.id()).contains(todayVisit.id());
        assertThat(service.upcomingFollowUps(owner, 30, today))
                .allSatisfy(v -> assertThat(v.visitAt().toLocalDate()).isAfterOrEqualTo(today));

        var reminders = service.scanTodayReminders(owner, today);
        assertThat(reminders).extracting(v -> v.kind()).contains("MEDICATION", "FOLLOW_UP");
        assertThat(reminders).allSatisfy(v -> {
            assertThat(v.templateKey()).isEqualTo("medication-reminder");
            assertThat(v.userId()).isEqualTo(30L);
        });
        assertThat(reminders).extracting(v -> v.data().get("detail").toString())
                .allSatisfy(text -> assertThat(text).doesNotContain("别家药"));
    }
}
