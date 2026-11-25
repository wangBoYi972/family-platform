package com.familyhome.iot.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class GatewayDeviceReportDTO {
  @NotEmpty
  @Valid
  private List<DeviceItem> devices;

  @Data
  public static class DeviceItem {
    @NotBlank
    private String entityId;

    @NotBlank
    private String entityType;

    private String deviceName;
    private String areaName;
    private String state;
    private Map<String, Object> attributes;
    private Boolean online;
  }
}
