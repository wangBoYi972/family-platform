package com.familyhome.archive.service;

import com.familyhome.archive.dto.FollowUpVisitSaveDTO;
import com.familyhome.archive.dto.MedicationCheckDTO;
import com.familyhome.archive.dto.MedicationPlanSaveDTO;
import com.familyhome.archive.vo.FollowUpVisitVO;
import com.familyhome.archive.vo.HealthReminderVO;
import com.familyhome.archive.vo.MedicationDoseVO;
import com.familyhome.archive.vo.MedicationPlanVO;
import com.familyhome.security.SessionPrincipal;
import java.time.LocalDate;
import java.util.List;

/** 用药与复诊服务；家庭编号和本人身份一律来自会话。 */
public interface HealthCareService {
    List<MedicationPlanVO> listMedications(SessionPrincipal p);

    MedicationPlanVO medicationDetail(SessionPrincipal p, String id);

    MedicationPlanVO createMedication(SessionPrincipal p, MedicationPlanSaveDTO input);

    void updateMedication(SessionPrincipal p, String id, MedicationPlanSaveDTO input);

    void deleteMedication(SessionPrincipal p, String id);

    /** 当天应服用的每一次，包含已勾选状态；不返回备注以外的医疗描述。 */
    List<MedicationDoseVO> todayMedications(SessionPrincipal p, LocalDate today);

    void checkMedication(SessionPrincipal p, String id, MedicationCheckDTO input, LocalDate today);

    List<FollowUpVisitVO> listFollowUps(SessionPrincipal p);

    FollowUpVisitVO followUpDetail(SessionPrincipal p, String id);

    FollowUpVisitVO createFollowUp(SessionPrincipal p, FollowUpVisitSaveDTO input);

    void updateFollowUp(SessionPrincipal p, String id, FollowUpVisitSaveDTO input);

    void deleteFollowUp(SessionPrincipal p, String id);

    List<FollowUpVisitVO> upcomingFollowUps(SessionPrincipal p, int days, LocalDate today);

    /** 可调用的提醒扫描：只扫会话所属家庭，产出「今天有药 / 今天复诊」，不直接发微信。 */
    List<HealthReminderVO> scanTodayReminders(SessionPrincipal p, LocalDate today);
}
