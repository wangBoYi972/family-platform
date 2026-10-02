package com.familyhome.file.model;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.*;
@Data
@TableName("family_file")
public class FamilyFile {
  @TableId(type=IdType.INPUT) private String id; private Long householdId; private Long ownerId; private String name; private String mime; private Long sizeBytes; private String visibility; private Boolean deleted; private LocalDateTime createdAt;
}

