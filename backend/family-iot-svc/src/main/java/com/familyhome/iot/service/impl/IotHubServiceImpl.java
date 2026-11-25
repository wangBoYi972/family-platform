package com.familyhome.iot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.familyhome.iot.dto.IotHubBindDTO;
import com.familyhome.iot.mapper.IotDeviceSnapshotMapper;
import com.familyhome.iot.mapper.IotHubMapper;
import com.familyhome.iot.model.IotDeviceSnapshot;
import com.familyhome.iot.model.IotHub;
import com.familyhome.iot.service.IotHubService;
import com.familyhome.iot.cloud.IotCloudClient;
import com.familyhome.iot.util.CloudHub;
import com.familyhome.iot.util.DemoHub;
import com.familyhome.iot.util.HubUrlValidator;
import com.familyhome.iot.util.TokenEncryptor;
import com.familyhome.iot.vo.HubDiscoveryVO;
import com.familyhome.iot.vo.IotHubVO;
import com.familyhome.iot.client.HomeAssistantClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

  @Autowired
  private IotCloudClient cloudClient;

  /** @Lazy 打破与 IotWebSocketServiceImpl 的循环依赖（后者依赖本服务读取中枢配置）。 */
  @Autowired
  @org.springframework.context.annotation.Lazy
  private com.familyhome.iot.service.IotWebSocketService wsService;

  /**
   * 自托管 / 平台与中枢同网部署时预置的凭据（由部署环境注入，不经过 UI）。
   * 公网 SaaS 场景下中枢由家庭侧网关主动注册，不使用这两个值。
   */
  @Value("${family.iot.auto-hub-url:}")
  private String autoHubUrl;

  @Value("${family.iot.auto-hub-token:}")
  private String autoHubToken;

  /**
   * 接入家庭设备通道。
   *
   * <p>优先云端直连（涂鸦等）：普通家庭不需要自建 Home Assistant。
   * 云通道就绪时自动绑定 {@code cloud://tuya}，前端不展示 IP/令牌。
   */
  @Override
  public HubDiscoveryVO autoDiscover(Long householdId) {
    IotHub existing = hubMapper.selectByHouseholdId(householdId);
    if (existing != null) {
      if (DemoHub.isDemo(existing.getBaseUrl()) || CloudHub.isCloud(existing.getBaseUrl())) {
        return HubDiscoveryVO.found(DemoHub.isDemo(existing.getBaseUrl()));
      }
      try {
        IotHub hub = getHubInternal(householdId);
        if (hub != null && homeAssistantClient.testConnection(hub.getBaseUrl(), hub.getAccessToken())) {
          return HubDiscoveryVO.found(false);
        }
      } catch (Exception e) {
        log.warn("家庭 {} 已绑定中枢但当前不可达：{}", householdId, e.getMessage());
      }
      return HubDiscoveryVO.offline();
    }

    // 推荐落地：云端直连（涂鸦 / sim）
    if (cloudClient != null && cloudClient.isReady()) {
      try {
        IotHubBindDTO dto = new IotHubBindDTO();
        dto.setBaseUrl(CloudHub.CLOUD_BASE_URL);
        dto.setAccessToken(cloudClient.providerId());
        bindHub(householdId, dto);
        HubDiscoveryVO vo = HubDiscoveryVO.found(false);
        vo.setMessage("已接通家庭设备云，可添加空调/电视");
        return vo;
      } catch (Exception e) {
        log.warn("家庭 {} 绑定云通道失败：{}", householdId, e.getMessage());
      }
    }

    // 可选：同网预置 HA（进阶，非普通用户路径）
    if (autoHubUrl != null && !autoHubUrl.isBlank()
        && autoHubToken != null && !autoHubToken.isBlank()) {
      try {
        if (hubUrlValidator.isAllowed(autoHubUrl)
            && homeAssistantClient.testConnection(autoHubUrl, autoHubToken)) {
          IotHubBindDTO dto = new IotHubBindDTO();
          dto.setBaseUrl(autoHubUrl);
          dto.setAccessToken(autoHubToken);
          bindHub(householdId, dto);
          return HubDiscoveryVO.found(false);
        }
      } catch (Exception e) {
        log.warn("家庭 {} 自动绑定预置中枢失败：{}", householdId, e.getMessage());
      }
    }

    HubDiscoveryVO vo = HubDiscoveryVO.notProvisioned();
    vo.setMessage("请添加设备：在涂鸦智能中配好红外伴侣/空调后，把设备 ID 填进 App；或联系管理员配置涂鸦云密钥");
    return vo;
  }

  @Override
  public IotHubVO getHub(Long householdId) {
    IotHub hub = hubMapper.selectByHouseholdId(householdId);
    if (hub == null) {
      IotHubVO vo = new IotHubVO();
      vo.setConfigured(false);
      vo.setDemo(false);
      vo.setCloud(false);
      return vo;
    }

    IotHubVO vo = new IotHubVO();
    vo.setId(hub.getId());
    vo.setConfigured(true);
    vo.setDemo(DemoHub.isDemo(hub.getBaseUrl()));
    vo.setCloud(CloudHub.isCloud(hub.getBaseUrl()));
    // 注意：不返回 baseUrl 给客户端，只返回 configured / demo / cloud 状态
    vo.setUpdatedAt(hub.getUpdatedAt());
    return vo;
  }

  @Override
  public IotHubVO bindHub(Long householdId, IotHubBindDTO dto) throws Exception {
    boolean demo = DemoHub.isDemo(dto.getBaseUrl());
    boolean cloud = CloudHub.isCloud(dto.getBaseUrl());

    // 1. 验证 URL 安全性（演示 / 云端伪协议不走网络）
    if (!demo && !cloud && !hubUrlValidator.isAllowed(dto.getBaseUrl())) {
      throw new IllegalArgumentException("中枢地址不在允许范围内（私网地址，或已加入远程白名单的主机）");
    }

    // 2. 测试连接（仅真实 HA；云端直连不测 HA）
    if (!demo && !cloud && !homeAssistantClient.testConnection(dto.getBaseUrl(), dto.getAccessToken())) {
      throw new RuntimeException("无法连接到 Home Assistant，请检查地址和令牌");
    }

    String baseUrl = demo ? DemoHub.DEMO_BASE_URL : (cloud ? CloudHub.CLOUD_BASE_URL : dto.getBaseUrl());

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

    // 绑定成功后尝试建立实时连接（演示 / 云端跳过）
    if (!demo && !cloud) {
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

    // 只清中枢同步的 HUB 快照；MANUAL / CLOUD 是用户自管，解绑后保留
    QueryWrapper<IotDeviceSnapshot> devices = new QueryWrapper<>();
    devices.eq("household_id", householdId).eq("device_source", "HUB");
    deviceSnapshotMapper.delete(devices);

    log.info("家庭 {} 的设备通道已解绑（已保留手动/云设备）", householdId);
  }

  @Override
  public boolean testConnection(IotHubBindDTO dto) throws Exception {
    if (DemoHub.isDemo(dto.getBaseUrl()) || CloudHub.isCloud(dto.getBaseUrl())) {
      return cloudClient != null && cloudClient.isReady();
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
    // 排除演示 / 云端：没有可连接的 HA WebSocket
    return hubMapper.selectList(null).stream()
        .filter(hub -> !DemoHub.isDemo(hub.getBaseUrl()) && !CloudHub.isCloud(hub.getBaseUrl()))
        .map(IotHub::getHouseholdId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
  }
}
