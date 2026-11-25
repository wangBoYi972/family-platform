package com.familyhome.iot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.familyhome.iot.dto.GatewayDeviceReportDTO;
import com.familyhome.iot.dto.GatewayRegisterDTO;
import com.familyhome.iot.dto.GatewayStateReportDTO;
import com.familyhome.iot.gateway.GatewaySessionRegistry;
import com.familyhome.iot.mapper.IotDeviceSnapshotMapper;
import com.familyhome.iot.mapper.IotGatewayMapper;
import com.familyhome.iot.mapper.IotHubMapper;
import com.familyhome.iot.mapper.IotPairingCodeMapper;
import com.familyhome.iot.model.IotDeviceSnapshot;
import com.familyhome.iot.model.IotGateway;
import com.familyhome.iot.model.IotHub;
import com.familyhome.iot.model.IotPairingCode;
import com.familyhome.iot.service.HomeGatewayService;
import com.familyhome.iot.service.IotDeviceService;
import com.familyhome.iot.service.IotHubService;
import com.familyhome.iot.util.GatewayHub;
import com.familyhome.iot.util.TokenEncryptor;
import com.familyhome.iot.vo.GatewayRegisterVO;
import com.familyhome.iot.vo.GatewayStatusVO;
import com.familyhome.iot.vo.IotHubVO;
import com.familyhome.iot.vo.PairingCodeVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class HomeGatewayServiceImpl implements HomeGatewayService {

  private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();

  @Autowired private IotPairingCodeMapper pairingCodeMapper;
  @Autowired private IotGatewayMapper gatewayMapper;
  @Autowired private IotHubMapper hubMapper;
  @Autowired private IotDeviceSnapshotMapper deviceSnapshotMapper;
  @Autowired private TokenEncryptor tokenEncryptor;
  @Autowired private IotDeviceService deviceService;
  @Autowired private IotHubService hubService;
  @Autowired(required = false) private GatewaySessionRegistry sessionRegistry;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  @Transactional
  public PairingCodeVO createPairingCode(Long householdId, Long userId) {
    String code = randomCode(8);
    IotPairingCode row = new IotPairingCode();
    row.setHouseholdId(householdId);
    row.setCodeHash(sha256(code));
    row.setCodeTail(code.substring(code.length() - 4));
    row.setExpiresAt(LocalDateTime.now().plusMinutes(15));
    row.setCreatedBy(userId);
    row.setCreatedAt(LocalDateTime.now());
    pairingCodeMapper.insert(row);

    PairingCodeVO vo = new PairingCodeVO();
    vo.setCode(code);
    vo.setExpiresAt(row.getExpiresAt());
    return vo;
  }

  @Override
  @Transactional
  public GatewayRegisterVO register(GatewayRegisterDTO dto) {
    String normalized = dto.getPairingCode().trim().toUpperCase(Locale.ROOT).replace("-", "");
    IotPairingCode pairing =
        pairingCodeMapper.selectOne(
            new QueryWrapper<IotPairingCode>().eq("code_hash", sha256(normalized)));
    if (pairing == null) {
      throw new IllegalArgumentException("配对码无效");
    }
    if (pairing.getUsedAt() != null) {
      throw new IllegalArgumentException("配对码已使用");
    }
    if (pairing.getExpiresAt().isBefore(LocalDateTime.now())) {
      throw new IllegalArgumentException("配对码已过期");
    }

    pairing.setUsedAt(LocalDateTime.now());
    pairingCodeMapper.updateById(pairing);

    // 重新配对前踢掉旧 WebSocket，避免命令仍打到旧网关进程
    if (sessionRegistry != null) {
      sessionRegistry.disconnect(pairing.getHouseholdId());
    }

    String gatewayId = "gw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String gatewayToken = "gtk_" + UUID.randomUUID().toString().replace("-", "");
    String displayName =
        dto.getDisplayName() == null || dto.getDisplayName().isBlank()
            ? "家庭网关"
            : dto.getDisplayName().trim();

    IotGateway existing =
        gatewayMapper.selectOne(
            new QueryWrapper<IotGateway>().eq("household_id", pairing.getHouseholdId()));
    if (existing != null) {
      existing.setGatewayId(gatewayId);
      existing.setTokenHash(sha256(gatewayToken));
      existing.setDisplayName(displayName);
      existing.setLastSeenAt(LocalDateTime.now());
      gatewayMapper.updateById(existing);
    } else {
      IotGateway gateway = new IotGateway();
      gateway.setHouseholdId(pairing.getHouseholdId());
      gateway.setGatewayId(gatewayId);
      gateway.setTokenHash(sha256(gatewayToken));
      gateway.setDisplayName(displayName);
      gateway.setLastSeenAt(LocalDateTime.now());
      gatewayMapper.insert(gateway);
    }

    // 绑定/覆盖为网关中枢（演示/HA 会被替换；解绑后可再选）
    String encrypted;
    try {
      encrypted = tokenEncryptor.encrypt(gatewayToken);
    } catch (Exception e) {
      throw new IllegalStateException("网关令牌加密失败", e);
    }
    IotHub hub = hubMapper.selectByHouseholdId(pairing.getHouseholdId());
    if (hub == null) {
      hub = new IotHub();
      hub.setHouseholdId(pairing.getHouseholdId());
      hub.setBaseUrl(GatewayHub.GATEWAY_BASE_URL);
      hub.setAccessToken(encrypted);
      hubMapper.insert(hub);
    } else {
      hub.setBaseUrl(GatewayHub.GATEWAY_BASE_URL);
      hub.setAccessToken(encrypted);
      hubMapper.updateById(hub);
    }

    // 清掉旧快照，等网关上报真设备
    deviceSnapshotMapper.delete(
        new QueryWrapper<IotDeviceSnapshot>().eq("household_id", pairing.getHouseholdId()));

    GatewayRegisterVO vo = new GatewayRegisterVO();
    vo.setHouseholdId(pairing.getHouseholdId());
    vo.setGatewayId(gatewayId);
    vo.setGatewayToken(gatewayToken);
    vo.setWsPath("/ws/iot-gateway");
    return vo;
  }

  @Override
  public IotGateway authenticate(String gatewayToken) {
    if (gatewayToken == null || gatewayToken.isBlank()) {
      throw new IllegalArgumentException("缺少网关令牌");
    }
    IotGateway gateway =
        gatewayMapper.selectOne(
            new QueryWrapper<IotGateway>().eq("token_hash", sha256(gatewayToken.trim())));
    if (gateway == null) {
      throw new IllegalArgumentException("网关令牌无效");
    }
    return gateway;
  }

  @Override
  public void touch(IotGateway gateway) {
    gateway.setLastSeenAt(LocalDateTime.now());
    gatewayMapper.updateById(gateway);
  }

  @Override
  @Transactional
  public void replaceDevices(Long householdId, GatewayDeviceReportDTO report) {
    deviceSnapshotMapper.delete(
        new QueryWrapper<IotDeviceSnapshot>().eq("household_id", householdId));
    for (GatewayDeviceReportDTO.DeviceItem item : report.getDevices()) {
      IotDeviceSnapshot snapshot = new IotDeviceSnapshot();
      snapshot.setHouseholdId(householdId);
      snapshot.setEntityId(item.getEntityId());
      snapshot.setEntityType(item.getEntityType());
      snapshot.setDeviceName(
          item.getDeviceName() == null || item.getDeviceName().isBlank()
              ? item.getEntityId()
              : item.getDeviceName());
      snapshot.setAreaName(
          item.getAreaName() == null || item.getAreaName().isBlank() ? "未分组" : item.getAreaName());
      snapshot.setState(item.getState() == null ? "off" : item.getState());
      try {
        snapshot.setAttributes(
            item.getAttributes() == null
                ? null
                : objectMapper.writeValueAsString(item.getAttributes()));
      } catch (Exception e) {
        throw new IllegalArgumentException("设备属性无法序列化");
      }
      snapshot.setIsOnline(item.getOnline() == null || item.getOnline());
      deviceSnapshotMapper.insert(snapshot);
    }
  }

  @Override
  public void updateDeviceState(
      Long householdId, String entityId, String state, java.util.Map<String, Object> attributes) {
    deviceService.updateDeviceState(householdId, entityId, state, attributes);
  }

  @Override
  @Transactional
  public void reportDevices(String gatewayToken, GatewayDeviceReportDTO report) {
    IotGateway gateway = authenticate(gatewayToken);
    touch(gateway);
    replaceDevices(gateway.getHouseholdId(), report);
  }

  @Override
  public void reportState(String gatewayToken, GatewayStateReportDTO report) {
    IotGateway gateway = authenticate(gatewayToken);
    touch(gateway);
    updateDeviceState(
        gateway.getHouseholdId(), report.getEntityId(), report.getState(), report.getAttributes());
  }

  @Override
  public GatewayStatusVO status(Long householdId) {
    IotHubVO hub = hubService.getHub(householdId);
    GatewayStatusVO vo = new GatewayStatusVO();
    vo.setConfigured(Boolean.TRUE.equals(hub.getConfigured()));
    vo.setGateway(Boolean.TRUE.equals(hub.getGateway()));
    vo.setOnline(isOnline(householdId));
    return vo;
  }

  @Override
  public boolean isOnline(Long householdId) {
    return sessionRegistry != null && sessionRegistry.isOnline(householdId);
  }

  private static String randomCode(int len) {
    StringBuilder sb = new StringBuilder(len);
    for (int i = 0; i < len; i++) {
      sb.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
    }
    return sb.toString();
  }

  private static String sha256(String raw) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (Exception e) {
      throw new IllegalStateException("无法计算摘要", e);
    }
  }
}
