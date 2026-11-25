package com.familyhome.iot.vo;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class PairingCodeVO {
  /** 明文配对码，只在创建时返回一次。 */
  private String code;

  private LocalDateTime expiresAt;
}
