package com.familyhome.iot.service;

import com.familyhome.iot.dto.IotHubBindDTO;
import com.familyhome.iot.vo.IotHubVO;

/**
 * IoT 中枢配置服务
 */
public interface IotHubService {

  /**
   * 获取中枢配置（仅返回是否已配置状态）
   */
  IotHubVO getHub(Long householdId);

  /**
   * 绑定中枢（仅管理员）
   */
  IotHubVO bindHub(Long householdId, IotHubBindDTO dto) throws Exception;

  /**
   * 解绑中枢（仅管理员）
   */
  void unbindHub(Long householdId);

  /**
   * 测试中枢连接（仅管理员）
   */
  boolean testConnection(IotHubBindDTO dto) throws Exception;

  /**
   * 获取中枢配置（内部用，包含令牌）
   */
  com.familyhome.iot.model.IotHub getHubInternal(Long householdId);

  /**
   * 列出所有已绑定中枢的家庭编号（供启动时建立实时连接用）。
   * 演示中枢不在此列——它没有可连接的 WebSocket 地址。
   */
  java.util.List<Long> listBoundHouseholdIds();
}
