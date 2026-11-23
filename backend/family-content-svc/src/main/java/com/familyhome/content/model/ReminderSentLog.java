package com.familyhome.content.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.*;

/**
 * 离线提醒发送记录：幂等控制，同一用户、同一业务对象、同一天只发一次。
 */
@Data
@TableName("reminder_sent_log")
public class ReminderSentLog {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收人用户ID */
    private Long userId;

    /** 业务类型: task / anniversary */
    private String businessType;

    /** 业务对象ID */
    private Long businessId;

    /** 发送日期（本地日期，非UTC） */
    private LocalDate sentDate;

    /** 发送结果: sent / skipped / no-quota / failed */
    private String result;

    private LocalDateTime createdAt;
}
