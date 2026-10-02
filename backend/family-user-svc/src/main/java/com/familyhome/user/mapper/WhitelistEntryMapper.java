package com.familyhome.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.user.model.WhitelistEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WhitelistEntryMapper extends BaseMapper<WhitelistEntry> {
    WhitelistEntry lockByPhone(@Param("phone") String phone);
}
