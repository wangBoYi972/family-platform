package com.familyhome.archive.model;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.*;
@Data
@TableName("family_archive")
public class FamilyArchive {
 @TableId(type=IdType.INPUT) private String id; private Long householdId; private Long ownerId; private String title; private String category; private String visibility; private String encryptedBody; private LocalDateTime createdAt;
}
