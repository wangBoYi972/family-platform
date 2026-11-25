package com.familyhome.iot.vo;

import lombok.Data;

@Data
public class GatewayStatusVO {
  private Boolean configured;
  private Boolean gateway;
  private Boolean online;
}
