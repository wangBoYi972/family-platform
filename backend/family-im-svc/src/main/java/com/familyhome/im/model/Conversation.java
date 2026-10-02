package com.familyhome.im.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.*;
import lombok.Data;

@TableName("im_conversation")
@Data
public class Conversation {
    @TableId(type = IdType.INPUT)
    private String id;

    private Long householdId;
    private String kind;
    private Long userA;
    private Long userB;
    private String title;
    private LocalDateTime createdAt;
}
