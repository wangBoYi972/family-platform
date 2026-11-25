package com.familyhome.iot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 按涂鸦等云平台设备 ID 导入到本家庭。 */
@Data
public class IotCloudImportDTO {

  @NotBlank(message = "云设备 ID 不能为空")
  @Size(max = 64, message = "云设备 ID 最多 64 字")
  private String cloudDeviceId;
}
