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
    /** 撤回标记：撤回时置 true 并清空 body，消息本身保留（分页与占位都需要）。 */
    private Boolean deleted;
}
