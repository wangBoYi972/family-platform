package com.familyhome.iot.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 命令日志 VO
 */
@Data
public class IotCommandLogVO {

  private Long id;

  private String entityId;

  private String commandType;

  private Boolean success;

  private String errorMessage;

  private LocalDateTime createdAt;
}
