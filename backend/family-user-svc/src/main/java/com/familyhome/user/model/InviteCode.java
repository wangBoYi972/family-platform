package com.familyhome.user.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.*;
import lombok.Data;

/** 注册邀请码。库内仅留哈希与展示用尾号，明文不落库。 */
@TableName("family_invite_code")
@Data
public class InviteCode {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long householdId;
    private String codeHash;
    private String codeHint;
    private String role;
    private Integer maxUses;
    private Integer usedCount;
    private Boolean enabled;
    private LocalDateTime expiresAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime usedAt;
    private String usedPhone;
}
