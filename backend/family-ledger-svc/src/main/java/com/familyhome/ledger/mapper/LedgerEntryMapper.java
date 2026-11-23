package com.familyhome.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.ledger.model.LedgerEntry;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LedgerEntryMapper extends BaseMapper<LedgerEntry> {}
