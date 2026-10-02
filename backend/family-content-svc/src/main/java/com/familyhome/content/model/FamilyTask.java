package com.familyhome.content.model;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.*;
@Data
@TableName("family_task")
public class FamilyTask {
  @TableId(type=IdType.AUTO) private Long id;
  private Long householdId; private String title; private Long creatorId; private Long assigneeId;
  private String assigneeName; private LocalDate deadline; private String status; private LocalDateTime createdAt;
}
