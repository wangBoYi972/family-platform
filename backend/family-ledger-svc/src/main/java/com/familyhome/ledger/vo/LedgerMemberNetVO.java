package com.familyhome.ledger.vo;

/**
 * 单个成员的月度净额。正数表示该收钱，负数表示该付钱；单位为分。
 */
public record LedgerMemberNetVO(
        Long memberId,
        String displayName,
        Long incomeCents,
        Long paidCents,
        Long shareCents,
        Long netCents) {}
