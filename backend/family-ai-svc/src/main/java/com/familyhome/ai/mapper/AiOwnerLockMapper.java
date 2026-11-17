package com.familyhome.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.ai.model.AiOwnerLock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiOwnerLockMapper extends BaseMapper<AiOwnerLock> {

    /** 锁住该用户的导入占位行；调用方须已在事务里。 */
    @Select("SELECT household_id AS householdId, user_id AS userId FROM ai_owner_lock "
            + "WHERE household_id = #{householdId} AND user_id = #{userId} FOR UPDATE")
    AiOwnerLock lockForUpdate(@Param("householdId") long householdId, @Param("userId") long userId);
}
