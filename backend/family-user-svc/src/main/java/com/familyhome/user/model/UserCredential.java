package com.familyhome.user.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.ToString;

@Data
@TableName("user_credential")
public class UserCredential {
    @TableId(type = IdType.INPUT)
    private String phone;

    @ToString.Exclude private String passwordHash;
    private int failedAttempts;
    private LocalDateTime lockedUntil;
    private LocalDateTime changedAt;
}
