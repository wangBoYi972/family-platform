package com.familyhome.ledger.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ledger_entry")
public class LedgerEntry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long householdId;
    private String type;
    private Long amountCents;
    private String category;
    private String note;
    private LocalDate occurredOn;
    private Long createdBy;
    private Long payerId;
    private LocalDateTime createdAt;
}
