package com.familyhome.im.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.*;
import lombok.Data;

@TableName("im_message")
@Data
public class ChatMessage {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;
    private Long householdId;
    private Long senderId;
    private String clientId;
    private String kind;
    private String body;
    private LocalDateTime createdAt;
}
