package com.familyhome.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.iot.model.IotDeviceSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/**
 * 设备快照 Mapper
 */
@Mapper
public interface IotDeviceSnapshotMapper extends BaseMapper<IotDeviceSnapshot> {

  /**
   * 查询家庭的所有设备
   */
  List<IotDeviceSnapshot> selectByHouseholdId(@Param("householdId") Long householdId);

  /**
   * 查询特定区域的设备
   */
  List<IotDeviceSnapshot> selectByHouseholdAndArea(
      @Param("householdId") Long householdId, @Param("areaName") String areaName);
}
