package com.familyhome.content.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.*;
import lombok.Data;

@Data
public class ContentEntry {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long householdId;
    private Long creatorId;
    private String title;
    private String body;
    private LocalDate eventDate;
    private Boolean yearly;
    private Boolean pinned;
    private String visibility;
    private String fileIds;
    private Integer likes;
    private LocalDateTime createdAt;
}
