package com.familyhome.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.user.model.Household;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HouseholdMapper extends BaseMapper<Household> {
    Long lock(@Param("id") long id);
}
