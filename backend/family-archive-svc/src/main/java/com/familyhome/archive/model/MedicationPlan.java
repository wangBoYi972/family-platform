package com.familyhome.archive.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 用药计划：药名、剂量、备注只以密文存放，时间/日期用于查询。 */
@Data
@TableName("medication_plan")
public class MedicationPlan {
    @TableId(type = IdType.INPUT) private String id;
    private Long householdId;
    private Long ownerId;
    private String encryptedBody;
    /** 逗号分隔的 HH:mm，排序后入库。 */
    private String doseTimes;
    private LocalDate startDate;
    private LocalDate endDate;
    private String visibility;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
