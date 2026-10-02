package com.familyhome.media.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

@TableName("media_session")
@Data
public class MediaSession {
    @TableId(type = IdType.INPUT)
    private String id;

    private Long householdId;
    private Long creatorId;
    private String kind;
    private String status;
    private LocalDateTime createdAt;
}
