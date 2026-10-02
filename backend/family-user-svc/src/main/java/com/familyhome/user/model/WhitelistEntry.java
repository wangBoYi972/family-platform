package com.familyhome.user.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.*;
import lombok.Data;

@TableName("family_white_list")
@Data
public class WhitelistEntry {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String phone;
    private Long householdId;
    private String role;
    private Boolean enabled;
}
