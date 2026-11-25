package com.familyhome.iot.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 手动添加/编辑设备请求
 *
 * <p>手动设备不依赖 Home Assistant，状态保存在本服务快照表里，用于：
 * 没有真实中枢时体验、或把不支持的设备（电视/空调/音响）纳入统一控制入口。
 */
@Data
public class IotDeviceUpsertDTO {

  /** 设备名称，如"客厅空调" */
  @NotBlank(message = "设备名称不能为空")
  @Size(max = 64, message = "设备名称最多 64 字")
  private String deviceName;

  /** 实体类型：light/switch/climate/media_player/sensor */
  @NotBlank(message = "设备类型不能为空")
  private String entityType;

  /** 品牌，如"格力"/"小米"。可选 */
  @Size(max = 64, message = "品牌最多 64 字")
  private String brand;

  /** 区域，如"客厅"。留空归入"自定义" */
  @Size(max = 32, message = "区域名称最多 32 字")
  private String areaName;

  /** 初始状态，默认 off */
  private String state;

  /** 初始属性（温度、风速、音量、频道等），JSON 对象 */
  private JsonNode attributes;
}
