package com.familyhome.iot.cloud;

import lombok.Data;

@Data
public class CloudDeviceInfo {
  private String cloudDeviceId;
  private String name;
  private String brand;
  private String category;
  private boolean online;

  /** 映射到本平台 entityType：climate / media_player / light / switch / sensor */
  private String entityType;
}
