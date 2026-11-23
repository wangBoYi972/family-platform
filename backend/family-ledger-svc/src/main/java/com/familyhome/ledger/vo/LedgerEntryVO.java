package com.familyhome.ledger.vo;

import java.time.LocalDate;
import java.util.List;

public record LedgerEntryVO(
        Long id,
        String type,
        Long amountCents,
        String category,
        String note,
        LocalDate occurredOn,
        Long createdBy,
        Long payerId,
        List<LedgerSplitVO> splits) {}
