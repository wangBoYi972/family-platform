package com.familyhome.media.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 通话参与者；state 决定其在房间里的可见性与是否收信令。 */
@TableName("call_participant")
@Data
public class CallParticipant {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String callId;
    private Long userId;

    /** INVITED / JOINED / LEFT / REJECTED / MISSED。 */
    private String state;

    private Boolean micOn;
    private Boolean camOn;

    private LocalDateTime invitedAt;
    private LocalDateTime joinedAt;
    private LocalDateTime leftAt;
}
