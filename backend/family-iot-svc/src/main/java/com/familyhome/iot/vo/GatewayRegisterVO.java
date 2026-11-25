package com.familyhome.iot.vo;

import lombok.Data;

@Data
public class GatewayRegisterVO {
  private Long householdId;
  private String gatewayId;
  /** 明文网关令牌，只在注册时返回一次，之后用于 WS/上报鉴权。 */
  private String gatewayToken;
  private String wsPath;
}
