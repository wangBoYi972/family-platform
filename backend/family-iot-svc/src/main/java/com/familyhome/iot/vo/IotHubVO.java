package com.familyhome.iot.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 中枢配置 VO（不暴露令牌）
 */
@Data
public class IotHubVO {

  private Long id;

  // 中枢地址（不返回令牌）
  private String baseUrl;

  // 是否已配置中枢
  private Boolean configured;

  // 是否为内置演示中枢（没有真实 Home Assistant 时用来体验）
  private Boolean demo;

  private LocalDateTime updatedAt;
}
