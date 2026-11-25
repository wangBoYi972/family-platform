package com.familyhome.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.ai.model.AiOwnerLock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiOwnerLockMapper extends BaseMapper<AiOwnerLock> {

    /** 锁住该用户的导入占位行；调用方须已在事务里。SQL 见 resources/mapper/AiOwnerLockMapper.xml。 */
    AiOwnerLock lockForUpdate(@Param("householdId") long householdId, @Param("userId") long userId);
}
