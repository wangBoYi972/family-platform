package com.familyhome.archive.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 某天某个时刻的服药勾选；数据库唯一键保证重复勾选幂等。 */
@Data
@TableName("medication_check")
public class MedicationCheck {
    @TableId(type = IdType.AUTO) private Long id;
    private Long householdId;
    private String planId;
    private LocalDate checkDate;
    private String doseTime;
    private Long checkedBy;
    private LocalDateTime createdAt;
}
