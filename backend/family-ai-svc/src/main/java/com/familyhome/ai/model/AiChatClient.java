package com.familyhome.ai.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 一次提问的客户端幂等键。主键冲突表示这句已经落过库。 */
@TableName("ai_chat_client")
@Data
public class AiChatClient {
    private Long householdId;
    private Long userId;
    private String clientId;
}
