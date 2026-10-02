package com.familyhome.archive.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.*;
import lombok.Data;

@TableName("archive_access_log")
@Data
public class ArchiveAccessLog {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long householdId;
    private Long userId;
    private String archiveId;
    private String action;
    private LocalDateTime createdAt;
}
