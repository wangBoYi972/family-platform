package com.familyhome.iot.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.Data;

@Data
public class GatewayStateReportDTO {
  @NotBlank
  private String entityId;

  @NotBlank
  private String state;

  private Map<String, Object> attributes;
}
