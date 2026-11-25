package com.familyhome.iot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class GatewayRegisterDTO {
  @NotBlank
  @Size(min = 6, max = 32)
  private String pairingCode;

  @Size(max = 128)
  private String displayName;
}
