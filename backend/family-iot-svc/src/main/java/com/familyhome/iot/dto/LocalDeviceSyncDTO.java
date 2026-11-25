package com.familyhome.iot.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 手机局域网拉完设备后回写云端快照。允许空列表（中枢在线但无相关实体）。
 */
@Data
public class LocalDeviceSyncDTO {
  @NotNull
  @Valid
  private List<DeviceItem> devices = new ArrayList<>();

  @Data
  public static class DeviceItem {
    @jakarta.validation.constraints.NotBlank
    private String entityId;

    @jakarta.validation.constraints.NotBlank
    private String entityType;

    private String deviceName;
    private String areaName;
    private String state;
    private Map<String, Object> attributes;
    private Boolean online;
  }
}
