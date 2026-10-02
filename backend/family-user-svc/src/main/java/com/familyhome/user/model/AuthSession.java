package com.familyhome.user.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.*;
import lombok.Data;

@TableName("auth_session")
@Data
public class AuthSession {
    @TableId(type = IdType.INPUT)
    private String id;

    private Long userId;
    private Long householdId;
    private String role;
    private String client;
    private LocalDateTime expiresAt;
    private Boolean revoked;
}
