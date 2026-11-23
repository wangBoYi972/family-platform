package com.familyhome.iot.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * IoT 中枢配置（Home Assistant）
 */
@Data
@TableName("iot_hub")
public class IotHub {
  @TableId(type = IdType.AUTO)
  private Long id;

  // 家庭编号
  private Long householdId;

  // Home Assistant 基础 URL
  private String baseUrl;

  // Home Assistant 长期访问令牌（加密存储）
  private String accessToken;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
