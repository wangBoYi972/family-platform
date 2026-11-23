package com.familyhome.iot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 执行命令请求
 */
@Data
public class IotCommandDTO {

  @NotBlank(message = "命令类型不能为空")
  private String commandType;

  // 额外参数（如亮度值）
  private JsonNode parameters;
}
