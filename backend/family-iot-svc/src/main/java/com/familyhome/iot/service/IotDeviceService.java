package com.familyhome.iot.service;

import com.familyhome.iot.dto.LocalDeviceSyncDTO;
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
   * 手机在局域网拉完设备后回写云端快照，供家人共同查看。
   * 仅允许 phone-local（私网 HA）中枢；演示/网关会拒绝。
   */
  void replaceLocalDevices(Long householdId, LocalDeviceSyncDTO report);
}
