package com.familyhome.iot.vo;

import lombok.Data;

/**
 * 家庭成员在手机上直连局域网中枢所需凭据（仅私网 phone-local 模式返回）。
 */
@Data
public class IotHubAccessVO {
  private String baseUrl;
  private String accessToken;
  private Boolean phoneLocal;
}
