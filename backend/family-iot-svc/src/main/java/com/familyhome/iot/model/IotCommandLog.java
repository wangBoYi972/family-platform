package com.familyhome.iot.model;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 命令执行日志
 */
@Data
@TableName("iot_command_log")
public class IotCommandLog {
  @TableId(type = IdType.AUTO)
  private Long id;

  // 家庭编号
  private Long householdId;

  // 执行用户 ID
  private Long userId;

  // 目标 entity_id
  private String entityId;

  // 命令类型（turn_on/turn_off/set_brightness 等）
  private String commandType;

  // 领域（light/switch/scene）
  private String commandDomain;

  // 服务名（turn_on/turn_off 等）
  private String commandService;

  // 请求参数（JSON）
  @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
  private JsonNode requestData;

  // 执行是否成功
  private Boolean success;

  // 错误信息
  private String errorMessage;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;
}
