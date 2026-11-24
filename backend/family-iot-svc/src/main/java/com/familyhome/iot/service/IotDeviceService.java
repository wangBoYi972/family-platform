package com.familyhome.iot.service;

import com.familyhome.iot.dto.IotDeviceUpsertDTO;
import com.familyhome.iot.vo.AreaDevicesVO;
import com.familyhome.iot.vo.IotDeviceVO;
import java.util.List;
import java.util.Map;

/**
 * 设备/场景管理服务接口
 */
public interface IotDeviceService {

  /**
   * 获取所有设备（按区域分组）
   */
  List<AreaDevicesVO> getDevicesByArea(Long householdId);

  /**
   * 获取所有场景
   */
  List<IotDeviceVO> getScenes(Long householdId);

  /**
   * 同步 Home Assistant 中的所有设备
   */
  void syncDevices(Long householdId) throws Exception;

  /**
   * 更新设备状态（从 WebSocket 事件）
   */
  void updateDeviceState(Long householdId, String entityId, String state, Map<String, Object> attributes);

  /**
   * 标记中枢离线，所有设备离线
   */
  void markHubOffline(Long householdId);

  /**
   * 手动添加一个设备（本地模拟，不依赖中枢）
   */
  IotDeviceVO addManualDevice(Long householdId, IotDeviceUpsertDTO dto);

  /**
   * 编辑手动设备。只能编辑 device_source=MANUAL 的设备。
   */
  IotDeviceVO updateManualDevice(Long householdId, String entityId, IotDeviceUpsertDTO dto);

  /**
   * 删除手动设备。只能删除 device_source=MANUAL 的设备。
   */
  void deleteManualDevice(Long householdId, String entityId);
}
