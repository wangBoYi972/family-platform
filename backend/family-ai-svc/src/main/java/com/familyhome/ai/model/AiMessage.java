package com.familyhome.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 某个家庭成员与小助手的一轮发言。按用户隔离，不进 IM 消息库。 */
@TableName("ai_message")
@Data
public class AiMessage {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long householdId;
    private Long userId;
    /** user 或 assistant。 */
    private String role;
    private String content;
    /** 客户端提问幂等键；助手回答为空。 */
    private String clientId;
    private LocalDateTime createdAt;
}
