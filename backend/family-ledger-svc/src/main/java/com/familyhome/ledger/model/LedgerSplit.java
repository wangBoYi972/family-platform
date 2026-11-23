package com.familyhome.ledger.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ledger_split")
public class LedgerSplit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long entryId;
    private Long householdId;
    private Long memberId;
    private Long amountCents;
}
