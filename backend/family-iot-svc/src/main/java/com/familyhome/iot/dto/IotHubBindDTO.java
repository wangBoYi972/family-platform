package com.familyhome.iot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 绑定中枢请求
 */
@Data
public class IotHubBindDTO {

  @NotBlank(message = "Home Assistant 地址不能为空")
  private String baseUrl;

  @NotBlank(message = "访问令牌不能为空")
  private String accessToken;

  /**
   * 手机已在家里 WiFi 测通中枢。云端跳过连通性探测（云端进不了家庭局域网）。
   */
  private Boolean phoneLocal;
}
