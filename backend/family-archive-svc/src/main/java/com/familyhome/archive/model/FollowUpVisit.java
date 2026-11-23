package com.familyhome.archive.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 复诊：标题、医院科室、备注加密；就诊时刻与提前提醒天数用于查询。 */
@Data
@TableName("follow_up_visit")
public class FollowUpVisit {
    @TableId(type = IdType.INPUT) private String id;
    private Long householdId;
    private Long ownerId;
    private String encryptedBody;
    private LocalDateTime visitAt;
    private Integer remindDays;
    private String visibility;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
