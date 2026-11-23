package com.familyhome.ledger.dto;

/** 一条分摊：成员与金额都由客户端提交，服务端校验成员资格与总和。 */
public record LedgerSplitDTO(Long memberId, Long amountCents) {}
