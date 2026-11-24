package com.familyhome.iot.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 设备/场景最近一次状态快照
 */
@Data
@TableName("iot_device_snapshot")
public class IotDeviceSnapshot {
  @TableId(type = IdType.AUTO)
  private Long id;

  // 家庭编号
  private Long householdId;

  // Home Assistant entity_id（如 light.living_room_light）
  private String entityId;

  // 实体类型（light/switch/sensor/scene/climate/media_player）
  private String entityType;

  // 设备名称
  private String deviceName;

  // 设备品牌（如：米家 / 格力 / 海尔 / Sony）
  private String brand;

  // 区域名称（用于分组）
  private String areaName;

  // 当前状态（on/off/数值等）
  private String state;

  // 额外属性（JSON 文本）：亮度、温度等
  //
  // 这里刻意用 String 而不是 JsonNode：MyBatis-Plus 的 JacksonTypeHandler 对 JsonNode
  // 只能写不能读（select 回来恒为 null，前端亮度条永远是 0），换成文本字段在 VO 层再解析。
  private String attributes;

  // 设备来源：HUB（中枢同步）/ MANUAL（手动添加）
  // 手动设备是本地模拟设备，不依赖真实中枢，同步时不会被覆盖。
  private String deviceSource;

  // 中枢是否在线
  @TableField(value = "is_online", fill = FieldFill.INSERT)
  private Boolean isOnline;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
