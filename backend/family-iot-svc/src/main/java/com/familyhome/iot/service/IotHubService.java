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
}
