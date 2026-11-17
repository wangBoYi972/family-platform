package com.familyhome.ai.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 每个用户一条导入锁行；空库补传前 FOR UPDATE 住它，避免两台设备各写一份。 */
@TableName("ai_owner_lock")
@Data
public class AiOwnerLock {
    private Long householdId;
    private Long userId;
}
