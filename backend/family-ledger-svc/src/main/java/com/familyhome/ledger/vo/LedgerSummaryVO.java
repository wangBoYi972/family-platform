package com.familyhome.ledger.vo;

import java.util.List;

/** 月度汇总全部在服务端基于整月数据计算，与前端当前加载的分页无关。 */
public record LedgerSummaryVO(
        String month,
        Long incomeCents,
        Long expenseCents,
        List<LedgerMemberNetVO> members) {}
