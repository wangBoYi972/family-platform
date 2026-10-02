package com.familyhome.user.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.*;
import lombok.Data;

@TableName("family_household")
@Data
public class Household {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private LocalDateTime createdAt;
}
