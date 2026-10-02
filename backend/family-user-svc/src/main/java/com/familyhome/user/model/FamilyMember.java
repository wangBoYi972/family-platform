package com.familyhome.user.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.*;
import lombok.Data;

@TableName("family_member")
@Data
public class FamilyMember {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long householdId;
    private Long userId;
    private String role;
    private LocalDateTime createdAt;
}
