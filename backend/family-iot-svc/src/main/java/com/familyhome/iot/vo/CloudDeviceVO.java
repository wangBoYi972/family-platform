package com.familyhome.iot.vo;

import lombok.Data;

/** 云账号下可导入的设备（涂鸦等）。 */
@Data
public class CloudDeviceVO {
  private String cloudDeviceId;
  private String name;
  private String brand;
  private String category;
  private boolean online;
  private String entityType;
  /** 本家庭是否已添加 */
  private boolean alreadyAdded;
}
