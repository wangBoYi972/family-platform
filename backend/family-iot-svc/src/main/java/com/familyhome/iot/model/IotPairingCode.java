package com.familyhome.iot.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("iot_pairing_code")
public class IotPairingCode {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long householdId;
  private String codeHash;
  private String codeTail;
  private LocalDateTime expiresAt;
  private LocalDateTime usedAt;
  private Long createdBy;
  private LocalDateTime createdAt;
}
