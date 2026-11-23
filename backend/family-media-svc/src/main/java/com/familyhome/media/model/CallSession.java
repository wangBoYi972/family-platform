package com.familyhome.media.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 一次通话会话。SDP 与 ICE 候选不落库，只在信令通道中转发。 */
@TableName("call_session")
@Data
public class CallSession {
    @TableId(type = IdType.INPUT)
    private String id;

    private Long householdId;

    /** DIRECT 一对一，GROUP 多人网状。 */
    private String mode;

    /** AUDIO / VIDEO。 */
    private String kind;

    /** RINGING / ACTIVE / ENDED。 */
    private String status;

    private Long callerId;

    /** REJECTED / NO_ANSWER / HANGUP / LEFT / BUSY / ERROR。 */
    private String endReason;

    /** 是否正在录音。全员可见，避免有人在不知情的情况下被录。 */
    private Boolean recording;

    /** 谁开的录音；停止后置空。 */
    private Long recordedBy;

    private LocalDateTime createdAt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
}
