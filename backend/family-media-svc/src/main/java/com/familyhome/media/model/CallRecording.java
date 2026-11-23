package com.familyhome.media.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 一次通话的录音与纪要。音频本体在文件服务，这里只有引用与文本。 */
@TableName("call_recording")
@Data
public class CallRecording {
    @TableId(type = IdType.INPUT)
    private String id;

    private String callId;

    private Long householdId;

    private String title;

    /** UPLOADED / TRANSCRIBING / READY / FAILED。 */
    private String status;

    private Integer durationMs;

    /** 提交录音的人；只有通话参与者能提交。 */
    private Long recordedBy;

    /** 全员知情同意标记，未勾选不允许生成纪要。 */
    private Boolean consentConfirmed;

    private String transcript;

    private String minutes;

    private String error;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
