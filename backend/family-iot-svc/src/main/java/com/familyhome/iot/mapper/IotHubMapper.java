package com.familyhome.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.iot.model.IotHub;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * IoT 中枢 Mapper
 */
@Mapper
public interface IotHubMapper extends BaseMapper<IotHub> {

  /**
   * 根据家庭编号查询中枢配置
   */
  IotHub selectByHouseholdId(@Param("householdId") Long householdId);
}
