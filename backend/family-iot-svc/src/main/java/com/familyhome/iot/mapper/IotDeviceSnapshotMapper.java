package com.familyhome.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.iot.model.IotDeviceSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * 设备快照 Mapper
 */
@Mapper
public interface IotDeviceSnapshotMapper extends BaseMapper<IotDeviceSnapshot> {

  /**
   * 查询家庭的所有设备
   */
  @Select("SELECT * FROM iot_device_snapshot WHERE household_id = #{householdId}")
  List<IotDeviceSnapshot> selectByHouseholdId(Long householdId);

  /**
   * 查询特定区域的设备
   */
  @Select("SELECT * FROM iot_device_snapshot WHERE household_id = #{householdId} AND area_name = #{areaName}")
  List<IotDeviceSnapshot> selectByHouseholdAndArea(Long householdId, String areaName);
}
