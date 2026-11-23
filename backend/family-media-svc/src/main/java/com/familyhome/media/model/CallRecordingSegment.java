package com.familyhome.media.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 录音分段：客户端每 3 分钟切一段，既绕开单次转写上限，也让失败只重试一段。 */
@TableName("call_recording_segment")
@Data
public class CallRecordingSegment {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String recordingId;

    /** 从 1 开始的顺序号，转写稿按它拼接。 */
    private Integer seq;

    private String fileId;

    private Integer durationMs;

    private String transcript;
}
