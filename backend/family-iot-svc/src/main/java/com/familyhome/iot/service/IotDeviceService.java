package com.familyhome.iot.service;

import com.familyhome.iot.dto.IotDeviceUpsertDTO;
import com.familyhome.iot.vo.AreaDevicesVO;
import com.familyhome.iot.vo.DeviceTestResultVO;
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
   * 添加设备：不填 cloudDeviceId 为本地模拟；填写则为云端真机（涂鸦等）。
   */
  IotDeviceVO addManualDevice(Long householdId, IotDeviceUpsertDTO dto);

  /**
   * 编辑用户自管设备（MANUAL / CLOUD）。
   */
  IotDeviceVO updateManualDevice(Long householdId, String entityId, IotDeviceUpsertDTO dto);

  /**
   * 删除用户自管设备（MANUAL / CLOUD）。
   */
  void deleteManualDevice(Long householdId, String entityId);

  /** 列出云账号下可导入的设备（已添加的会在名称上标注）。 */
  java.util.List<com.familyhome.iot.cloud.CloudDeviceInfo> listCloudDevices(Long householdId) throws Exception;

  /** 按云设备 ID 导入到本家庭。 */
  IotDeviceVO importCloudDevice(Long householdId, String cloudDeviceId) throws Exception;

  /**
   * 探测单台设备是否真实响应。
   *
   * <p>真实中枢设备会实际读取 Home Assistant 上该实体的实时状态；
   * 手动添加/演示设备返回"本地模拟"说明，不访问任何硬件。
   * 只能探测本家庭名下的设备。
   */
  DeviceTestResultVO testDevice(Long householdId, String entityId);
}
