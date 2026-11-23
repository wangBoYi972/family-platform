package com.familyhome.ledger.dto;

import java.util.List;

/**
 * 记账请求。刻意不包含 householdId / createdBy：
 * 家庭与记账人只从已校验会话取，客户端传什么都不参与落库。
 */
public record LedgerEntrySaveDTO(
        String type,
        Long amountCents,
        String category,
        String note,
        String occurredOn,
        Long payerId,
        List<LedgerSplitDTO> splits) {}
