package com.familyhome.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.iot.model.IotHub;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * IoT 中枢 Mapper
 */
@Mapper
public interface IotHubMapper extends BaseMapper<IotHub> {

  /**
   * 根据家庭编号查询中枢配置
   */
  @Select("SELECT * FROM iot_hub WHERE household_id = #{householdId}")
  IotHub selectByHouseholdId(Long householdId);
}
