package com.familyhome.archive.controller;

import com.familyhome.archive.config.FamilyClock;
import com.familyhome.archive.dto.FollowUpVisitSaveDTO;
import com.familyhome.archive.dto.MedicationCheckDTO;
import com.familyhome.archive.dto.MedicationPlanSaveDTO;
import com.familyhome.archive.service.HealthCareService;
import com.familyhome.archive.vo.FollowUpVisitVO;
import com.familyhome.archive.vo.HealthReminderVO;
import com.familyhome.archive.vo.MedicationDoseVO;
import com.familyhome.archive.vo.MedicationPlanVO;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 用药与复诊接口：沿用网关已有的 /api/archives 路由，不新增服务或路由。 */
@RestController
@Validated
@RequestMapping("/api/archives")
public class HealthCareController {
    @Autowired private HealthCareService service;
    @Autowired private FamilyClock clock;

    @GetMapping("/medications")
    public ApiResponse<List<MedicationPlanVO>> medications(HttpServletRequest r) {
        return ApiResponse.success(service.listMedications(SessionPrincipal.from(r)));
    }

    @GetMapping("/medications/today")
    public ApiResponse<List<MedicationDoseVO>> medicationsToday(HttpServletRequest r) {
        return ApiResponse.success(
                service.todayMedications(SessionPrincipal.from(r), clock.today()));
    }

    @PostMapping("/medications")
    public ApiResponse<MedicationPlanVO> createMedication(
            HttpServletRequest r, @Valid @RequestBody MedicationPlanSaveDTO input) {
        return ApiResponse.success(service.createMedication(SessionPrincipal.from(r), input));
    }

    @GetMapping("/medications/{id}")
    public ApiResponse<MedicationPlanVO> medicationDetail(HttpServletRequest r, @PathVariable String id) {
        return ApiResponse.success(service.medicationDetail(SessionPrincipal.from(r), id));
    }

    @PutMapping("/medications/{id}")
    public ApiResponse<Void> updateMedication(
            HttpServletRequest r, @PathVariable String id, @Valid @RequestBody MedicationPlanSaveDTO input) {
        service.updateMedication(SessionPrincipal.from(r), id, input);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/medications/{id}")
    public ApiResponse<Void> deleteMedication(HttpServletRequest r, @PathVariable String id) {
        service.deleteMedication(SessionPrincipal.from(r), id);
        return ApiResponse.success(null);
    }

    @PostMapping("/medications/{id}/checks")
    public ApiResponse<Void> checkMedication(
            HttpServletRequest r, @PathVariable String id, @Valid @RequestBody MedicationCheckDTO input) {
        service.checkMedication(SessionPrincipal.from(r), id, input, clock.today());
        return ApiResponse.success(null);
    }

    @GetMapping("/follow-ups")
    public ApiResponse<List<FollowUpVisitVO>> followUps(HttpServletRequest r) {
        return ApiResponse.success(service.listFollowUps(SessionPrincipal.from(r)));
    }

    /** 未来 N 天窗口，默认 30 天；「今天」按家庭时区计算。 */
    @GetMapping("/follow-ups/upcoming")
    public ApiResponse<List<FollowUpVisitVO>> upcomingFollowUps(
            HttpServletRequest r, @RequestParam(defaultValue = "30") @Min(1) @Max(30) int days) {
        return ApiResponse.success(service.upcomingFollowUps(SessionPrincipal.from(r), days, clock.today()));
    }

    @PostMapping("/follow-ups")
    public ApiResponse<FollowUpVisitVO> createFollowUp(
            HttpServletRequest r, @Valid @RequestBody FollowUpVisitSaveDTO input) {
        return ApiResponse.success(service.createFollowUp(SessionPrincipal.from(r), input));
    }

    @GetMapping("/follow-ups/{id}")
    public ApiResponse<FollowUpVisitVO> followUpDetail(HttpServletRequest r, @PathVariable String id) {
        return ApiResponse.success(service.followUpDetail(SessionPrincipal.from(r), id));
    }

    @PutMapping("/follow-ups/{id}")
    public ApiResponse<Void> updateFollowUp(
            HttpServletRequest r, @PathVariable String id, @Valid @RequestBody FollowUpVisitSaveDTO input) {
        service.updateFollowUp(SessionPrincipal.from(r), id, input);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/follow-ups/{id}")
    public ApiResponse<Void> deleteFollowUp(HttpServletRequest r, @PathVariable String id) {
        service.deleteFollowUp(SessionPrincipal.from(r), id);
        return ApiResponse.success(null);
    }

    /** 供订阅消息链路调用的扫描入口；模板未配置时链路静默跳过，不弹微信授权。 */
    @GetMapping("/reminders/today")
    public ApiResponse<List<HealthReminderVO>> todayReminders(HttpServletRequest r) {
        return ApiResponse.success(service.scanTodayReminders(SessionPrincipal.from(r), clock.today()));
    }
}
