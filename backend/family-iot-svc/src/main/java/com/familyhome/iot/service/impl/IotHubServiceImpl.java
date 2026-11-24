package com.familyhome.iot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.familyhome.iot.dto.IotHubBindDTO;
import com.familyhome.iot.mapper.IotDeviceSnapshotMapper;
import com.familyhome.iot.mapper.IotHubMapper;
import com.familyhome.iot.model.IotDeviceSnapshot;
import com.familyhome.iot.model.IotHub;
import com.familyhome.iot.service.IotHubService;
import com.familyhome.iot.util.DemoHub;
import com.familyhome.iot.util.HubUrlValidator;
import com.familyhome.iot.util.TokenEncryptor;
import com.familyhome.iot.vo.IotHubVO;
import com.familyhome.iot.client.HomeAssistantClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * IoT 中枢配置服务实现
 */
@Slf4j
@Service
public class IotHubServiceImpl implements IotHubService {

  @Autowired
  private IotHubMapper hubMapper;

  @Autowired
  private HubUrlValidator hubUrlValidator;

  @Autowired
  private TokenEncryptor tokenEncryptor;

  @Autowired
  private HomeAssistantClient homeAssistantClient;

  @Autowired
  private IotDeviceSnapshotMapper deviceSnapshotMapper;

  /** @Lazy 打破与 IotWebSocketServiceImpl 的循环依赖（后者依赖本服务读取中枢配置）。 */
  @Autowired
  @org.springframework.context.annotation.Lazy
  private com.familyhome.iot.service.IotWebSocketService wsService;

  @Override
  public IotHubVO getHub(Long householdId) {
    IotHub hub = hubMapper.selectByHouseholdId(householdId);
    if (hub == null) {
      IotHubVO vo = new IotHubVO();
      vo.setConfigured(false);
      vo.setDemo(false);
      return vo;
    }

    IotHubVO vo = new IotHubVO();
    vo.setId(hub.getId());
    vo.setConfigured(true);
    vo.setDemo(DemoHub.isDemo(hub.getBaseUrl()));
    // 注意：不返回 baseUrl 给客户端，只返回 configured 状态
    vo.setUpdatedAt(hub.getUpdatedAt());
    return vo;
  }

  @Override
  public IotHubVO bindHub(Long householdId, IotHubBindDTO dto) throws Exception {
    boolean demo = DemoHub.isDemo(dto.getBaseUrl());

    // 1. 验证 URL 安全性（演示中枢不走网络，不需要校验地址）
    if (!demo && !hubUrlValidator.isAllowed(dto.getBaseUrl())) {
      throw new IllegalArgumentException("中枢地址不在允许范围内（私网地址，或已加入远程白名单的主机）");
    }

    // 2. 测试连接
    if (!demo && !homeAssistantClient.testConnection(dto.getBaseUrl(), dto.getAccessToken())) {
      throw new RuntimeException("无法连接到 Home Assistant，请检查地址和令牌");
    }

    String baseUrl = demo ? DemoHub.DEMO_BASE_URL : dto.getBaseUrl();

    // 3. 加密令牌
    String encryptedToken = tokenEncryptor.encrypt(dto.getAccessToken());

    // 4. 更新或新建配置
    IotHub existing = hubMapper.selectByHouseholdId(householdId);
    if (existing != null) {
      existing.setBaseUrl(baseUrl);
      existing.setAccessToken(encryptedToken);
      hubMapper.updateById(existing);
    } else {
      IotHub hub = new IotHub();
      hub.setHouseholdId(householdId);
      hub.setBaseUrl(baseUrl);
      hub.setAccessToken(encryptedToken);
      hubMapper.insert(hub);
    }

    // 绑定成功后尝试建立实时连接（演示中枢跳过；失败不影响绑定结果）
    if (!demo) {
      final Long hid = householdId;
      Thread t = new Thread(() -> {
        try {
          wsService.connect(hid);
        } catch (Exception e) {
          log.warn("家庭 {} 绑定后实时连接未建立（可依赖轮询兜底）: {}", hid, e.getMessage());
        }
      }, "iot-ws-connect-" + hid);
      t.setDaemon(true);
      t.start();
    }

    return getHub(householdId);
  }

  @Override
  public void unbindHub(Long householdId) {
    // 先断开实时连接，避免解绑后仍在接收并写入已清除的快照
    wsService.disconnect(householdId);

    QueryWrapper<IotHub> qw = new QueryWrapper<>();
    qw.eq("household_id", householdId);
    hubMapper.delete(qw);

    // 设备快照属于中枢的缓存，解绑后一并清掉，避免重新绑定前看到幽灵设备
    QueryWrapper<IotDeviceSnapshot> devices = new QueryWrapper<>();
    devices.eq("household_id", householdId);
    deviceSnapshotMapper.delete(devices);

    log.info("家庭 {} 的中枢已解绑", householdId);
  }

  @Override
  public boolean testConnection(IotHubBindDTO dto) throws Exception {
    if (DemoHub.isDemo(dto.getBaseUrl())) {
      return true;
    }
    if (!hubUrlValidator.isAllowed(dto.getBaseUrl())) {
      throw new IllegalArgumentException("中枢地址不在允许范围内（私网地址，或已加入远程白名单的主机）");
    }
    return homeAssistantClient.testConnection(dto.getBaseUrl(), dto.getAccessToken());
  }

  @Override
  public IotHub getHubInternal(Long householdId) {
    IotHub hub = hubMapper.selectByHouseholdId(householdId);
    if (hub != null && hub.getAccessToken() != null) {
      try {
        // 解密令牌
        hub.setAccessToken(tokenEncryptor.decrypt(hub.getAccessToken()));
      } catch (Exception e) {
        log.error("令牌解密失败", e);
        throw new RuntimeException("令牌解密失败");
      }
    }
    return hub;
  }

  @Override
  public java.util.List<Long> listBoundHouseholdIds() {
    // 排除演示中枢：demo:// 没有可连接的 WebSocket 地址
    return hubMapper.selectList(null).stream()
        .filter(hub -> !DemoHub.isDemo(hub.getBaseUrl()))
        .map(IotHub::getHouseholdId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
  }
}
