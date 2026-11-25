package com.familyhome.iot.service;

import com.familyhome.iot.dto.IotHubBindDTO;
import com.familyhome.iot.vo.HubDiscoveryVO;
import com.familyhome.iot.vo.IotHubVO;

/**
 * IoT 中枢配置服务
 */
public interface IotHubService {

  /**
   * 后台静默发现家庭中枢：地址与令牌由服务端底层自动获取并封装，
   * 结果只返回"是否发现"与面向用户的状态文案，绝不暴露 IP / 令牌给 UI。
   *
   * <p>发现成功时会顺带完成绑定与设备同步；发现失败时前端停留在扫描环节，
   * 提示"请检查中枢是否通电/连接同一WiFi"并提供重新扫描入口。
   */
  HubDiscoveryVO autoDiscover(Long householdId);

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
