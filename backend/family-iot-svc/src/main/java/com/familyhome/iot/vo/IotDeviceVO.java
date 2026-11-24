package com.familyhome.iot.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 设备 VO
 */
@Data
public class IotDeviceVO {

  private String entityId;

  // 设备名称
  private String deviceName;

  // 设备品牌
  private String brand;

  // 实体类型（light/switch/sensor/scene/climate/media_player）
  private String entityType;

  // 设备来源：HUB（中枢同步）/ MANUAL（手动添加，可编辑删除）
  private String deviceSource;

  // 区域名称
  private String areaName;

  // 当前状态
  private String state;

  // 额外属性（如亮度）
  private JsonNode attributes;

  // 中枢是否在线
  private Boolean isOnline;

  private LocalDateTime updatedAt;
}
