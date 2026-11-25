package com.familyhome.iot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.familyhome.iot.dto.IotDeviceUpsertDTO;
import com.familyhome.iot.mapper.IotDeviceSnapshotMapper;
import com.familyhome.iot.model.IotDeviceSnapshot;
import com.familyhome.iot.model.IotHub;
import com.familyhome.iot.service.IotDeviceService;
import com.familyhome.iot.service.IotHubService;
import com.familyhome.iot.vo.AreaDevicesVO;
import com.familyhome.iot.vo.DeviceStateEventVO;
import com.familyhome.iot.vo.DeviceTestResultVO;
import com.familyhome.iot.vo.IotDeviceVO;
import com.familyhome.iot.client.HomeAssistantClient;
import com.familyhome.iot.cloud.IotCloudClient;
import com.familyhome.iot.util.CloudHub;
import com.familyhome.iot.util.DemoHub;
import com.familyhome.messaging.OutboxEventStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 设备/场景服务实现
 */
@Slf4j
@Service
public class IotDeviceServiceImpl implements IotDeviceService {

  @Autowired
  private IotDeviceSnapshotMapper deviceSnapshotMapper;

  @Autowired
  private IotHubService hubService;

  @Autowired
  private HomeAssistantClient homeAssistantClient;

  @Autowired
  private IotCloudClient cloudClient;

  @Autowired(required = false)
  private OutboxEventStore outboxEventStore;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public List<AreaDevicesVO> getDevicesByArea(Long householdId) {
    List<IotDeviceSnapshot> devices = deviceSnapshotMapper.selectByHouseholdId(householdId);

    // 按区域分组
    Map<String, List<IotDeviceSnapshot>> grouped = devices.stream()
        .filter(d -> !d.getEntityType().equals("scene"))
        .collect(Collectors.groupingBy(
            d -> d.getAreaName() != null ? d.getAreaName() : "未分组"
        ));

    // 转换为 VO 列表
    return grouped.entrySet().stream()
        .map(entry -> new AreaDevicesVO(
            entry.getKey(),
            entry.getValue().stream()
                .map(this::toDeviceVO)
                .collect(Collectors.toList())
        ))
        .collect(Collectors.toList());
  }

  @Override
  public List<IotDeviceVO> getScenes(Long householdId) {
    List<IotDeviceSnapshot> scenes = deviceSnapshotMapper.selectByHouseholdId(householdId);
    return scenes.stream()
        .filter(s -> "scene".equals(s.getEntityType()))
        .map(this::toDeviceVO)
        .collect(Collectors.toList());
  }

  @Override
  public void syncDevices(Long householdId) throws Exception {
    IotHub hub = hubService.getHubInternal(householdId);
    if (hub == null) {
      throw new RuntimeException("设备通道未配置");
    }

    if (DemoHub.isDemo(hub.getBaseUrl())) {
      seedDemoDevices(householdId);
      log.info("演示中枢：已为家庭 {} 生成示例设备", householdId);
      return;
    }

    // 云端直连：设备由用户导入 / 填写涂鸦 ID，不从 HA 拉列表
    if (CloudHub.isCloud(hub.getBaseUrl())) {
      log.info("家庭 {} 使用云端直连，跳过 HA 同步", householdId);
      return;
    }

    try {
      JsonNode states = homeAssistantClient.getStates(hub);

      // 只清除中枢同步的旧快照；手动添加的设备是本地模拟设备，必须保留
      QueryWrapper<IotDeviceSnapshot> qw = new QueryWrapper<>();
      qw.eq("household_id", householdId);
      qw.eq("device_source", "HUB");
      deviceSnapshotMapper.delete(qw);

      // 插入新快照
      if (states.isArray()) {
        for (JsonNode state : states) {
          String entityId = state.get("entity_id").asText();
          String[] parts = entityId.split("\\.");
          if (parts.length != 2) continue;

          String domain = parts[0];

          // 过滤：只关心可控制的设备域（含空调/电视音响）
          if (!isRelevantDomain(domain)) continue;

          IotDeviceSnapshot snapshot = new IotDeviceSnapshot();
          snapshot.setHouseholdId(householdId);
          snapshot.setEntityId(entityId);
          snapshot.setEntityType(domain);
          snapshot.setDeviceName(state.path("attributes").path("friendly_name").asText(entityId));
          // 品牌优先取 manufacturer，其次 device_model 的厂商前缀兜底
          String manufacturer = state.path("attributes").path("manufacturer").asText("");
          snapshot.setBrand(manufacturer.isBlank() ? null : manufacturer);
          snapshot.setAreaName(state.path("attributes").path("area_id").asText());
          snapshot.setState(state.get("state").asText());
          snapshot.setAttributes(
              state.has("attributes") && !state.get("attributes").isNull()
                  ? state.get("attributes").toString()
                  : null);
          snapshot.setDeviceSource("HUB");
          // isOnline 和 createdAt/updatedAt 由 IotMetaObjectHandler 自动填充

          deviceSnapshotMapper.insert(snapshot);
        }
      }

      log.info("同步设备完成，家庭: {}", householdId);

    } catch (Exception e) {
      log.error("同步设备失败", e);
      throw e;
    }
  }

  @Override
  public void updateDeviceState(Long householdId, String entityId, String state, Map<String, Object> attributes) {
    IotDeviceSnapshot snapshot = deviceSnapshotMapper.selectOne(
        new QueryWrapper<IotDeviceSnapshot>()
            .eq("household_id", householdId)
            .eq("entity_id", entityId)
    );

    if (snapshot != null) {
      snapshot.setState(state);
      if (attributes != null) {
        try {
          snapshot.setAttributes(objectMapper.writeValueAsString(attributes));
        } catch (Exception e) {
          log.warn("序列化设备属性失败: {}", e.getMessage());
        }
      }
      snapshot.setIsOnline(true);
      deviceSnapshotMapper.updateById(snapshot);

      // 发布设备状态变化事件
      if (outboxEventStore != null) {
        try {
          DeviceStateEventVO eventPayload = new DeviceStateEventVO(
              entityId,
              snapshot.getDeviceName(),
              state,
              System.currentTimeMillis()
          );

          // 转换 VO 为 Map 供 Outbox 使用
          java.util.Map<String, Object> eventMap = objectMapper.convertValue(
              eventPayload,
              new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}
          );

          outboxEventStore.append(
              "device",
              entityId,
              "device.state",
              householdId,
              eventMap
          );
        } catch (Exception e) {
          log.error("发布设备状态事件失败", e);
        }
      }
    }
  }

  @Override
  public void markHubOffline(Long householdId) {
    // 只标记中枢同步设备离线；MANUAL / CLOUD 不依赖 HA WebSocket
    QueryWrapper<IotDeviceSnapshot> qw = new QueryWrapper<>();
    qw.eq("household_id", householdId);
    qw.eq("device_source", "HUB");
    qw.eq("is_online", true);

    IotDeviceSnapshot update = new IotDeviceSnapshot();
    update.setIsOnline(false);

    deviceSnapshotMapper.update(update, qw);
    log.warn("家庭 {} 中枢已离线，HUB 设备标记为离线", householdId);
  }

  /**
   * 演示中枢的内置设备。
   *
   * <p>没有真实 Home Assistant 时，家庭成员依然能看到设备并操作，交互路径与真实中枢一致，
   * 只是状态保存在本地快照里。
   */
  private void seedDemoDevices(Long householdId) throws Exception {
    // 只重置演示/中枢同步的设备，保留用户自管（MANUAL / CLOUD）
    QueryWrapper<IotDeviceSnapshot> qw = new QueryWrapper<>();
    qw.eq("household_id", householdId);
    qw.eq("device_source", "HUB");
    deviceSnapshotMapper.delete(qw);

    for (DemoDevice demo : DEMO_DEVICES) {
      IotDeviceSnapshot snapshot = new IotDeviceSnapshot();
      snapshot.setHouseholdId(householdId);
      snapshot.setEntityId(demo.entityId());
      snapshot.setEntityType(demo.entityId().substring(0, demo.entityId().indexOf('.')));
      snapshot.setDeviceName(demo.name());
      snapshot.setBrand(demoBrand(demo.entityId()));
      snapshot.setAreaName(demo.area());
      snapshot.setState(demo.state());
      snapshot.setAttributes(objectMapper.writeValueAsString(demo.attributes()));
      snapshot.setDeviceSource("HUB");
      snapshot.setIsOnline(true);
      deviceSnapshotMapper.insert(snapshot);
    }
  }

  private record DemoDevice(
      String entityId, String name, String area, String state, Map<String, Object> attributes) {}

  /** 演示设备的品牌，纯展示用。 */
  private static String demoBrand(String entityId) {
    if (entityId.contains("climate")) return "格力";
    if (entityId.contains("media_player")) return entityId.contains("tv") ? "Sony" : "Sonos";
    if (entityId.startsWith("light")) return "飞利浦";
    if (entityId.contains("humidifier")) return "德尔玛";
    if (entityId.contains("kettle")) return "美的";
    if (entityId.startsWith("sensor")) return "Aqara";
    return "小米";
  }

  private static final List<DemoDevice> DEMO_DEVICES =
      List.of(
          new DemoDevice(
              "light.living_room_main",
              "客厅主灯",
              "客厅",
              "on",
              Map.of("brightness", 204, "supported_features", 1)),
          new DemoDevice(
              "light.living_room_strip",
              "沙发氛围灯",
              "客厅",
              "off",
              Map.of("brightness", 120, "supported_features", 1)),
          new DemoDevice("switch.living_room_tv", "电视插座", "客厅", "off", Map.of()),
          // 空调：state=模式（cool/heat/off），温度/风速/当前温度在 attributes
          new DemoDevice(
              "climate.living_room_ac",
              "客厅空调",
              "客厅",
              "cool",
              Map.of(
                  "temperature", 26, "current_temperature", 24.5,
                  "hvac_mode", "cool", "fan_mode", "auto",
                  "hvac_modes", java.util.List.of("cool", "heat", "dry", "fan_only", "auto", "off"),
                  "fan_modes", java.util.List.of("auto", "low", "mid", "high"))),
          // 电视：state=off/on，频道/音量/静音在 attributes
          new DemoDevice(
              "media_player.living_room_tv",
              "客厅电视",
              "客厅",
              "off",
              Map.of(
                  "volume_level", 0.3, "is_volume_muted", false,
                  "source", "HDMI 1", "media_title", "",
                  "source_list", java.util.List.of("HDMI 1", "HDMI 2", "TV", "AV", "游戏机"))),
          new DemoDevice(
              "sensor.living_room_temp",
              "客厅温度",
              "客厅",
              "24.5",
              Map.of("unit_of_measurement", "°C")),
          new DemoDevice(
              "light.bedroom_ceiling",
              "主卧吸顶灯",
              "主卧",
              "off",
              Map.of("brightness", 180, "supported_features", 1)),
          new DemoDevice("switch.bedroom_humidifier", "加湿器", "主卧", "on", Map.of()),
          new DemoDevice(
              "media_player.bedroom_speaker",
              "主卧音响",
              "主卧",
              "off",
              Map.of(
                  "volume_level", 0.25, "is_volume_muted", false,
                  "source", "蓝牙", "media_title", "",
                  "source_list", java.util.List.of("蓝牙", "Wi-Fi", "AUX", "光纤"))),
          new DemoDevice("switch.kitchen_kettle", "电热水壶", "厨房", "off", Map.of()),
          new DemoDevice(
              "sensor.kitchen_smoke",
              "烟雾传感器",
              "厨房",
              "正常",
              Map.of("unit_of_measurement", "")),
          new DemoDevice("switch.balcony_curtain", "阳台窗帘", "阳台", "off", Map.of()),
          new DemoDevice("scene.away", "离家模式", "场景", "off", Map.of()),
          new DemoDevice("scene.movie", "影院模式", "场景", "off", Map.of()),
          new DemoDevice("scene.sleep", "晚安模式", "场景", "off", Map.of()));

  private IotDeviceVO toDeviceVO(IotDeviceSnapshot snapshot) {
    IotDeviceVO vo = new IotDeviceVO();
    vo.setEntityId(snapshot.getEntityId());
    vo.setDeviceName(snapshot.getDeviceName());
    vo.setBrand(snapshot.getBrand());
    vo.setEntityType(snapshot.getEntityType());
    vo.setDeviceSource(snapshot.getDeviceSource());
    vo.setCloudDeviceId(snapshot.getCloudDeviceId());
    vo.setCloudProvider(snapshot.getCloudProvider());
    vo.setAreaName(snapshot.getAreaName());
    vo.setState(snapshot.getState());
    vo.setAttributes(readAttributes(snapshot.getAttributes()));
    vo.setIsOnline(snapshot.getIsOnline());
    vo.setUpdatedAt(snapshot.getUpdatedAt());
    return vo;
  }

  /** 库里的 attributes 是 JSON 文本，VO 对外仍给 JsonNode，前端拿 attributes.brightness 直接用。 */
  private JsonNode readAttributes(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return objectMapper.readTree(raw);
    } catch (Exception e) {
      log.warn("解析设备属性失败: {}", e.getMessage());
      return null;
    }
  }

  private boolean isRelevantDomain(String domain) {
    return "light".equals(domain) || "switch".equals(domain)
        || "sensor".equals(domain) || "scene".equals(domain)
        || "climate".equals(domain) || "media_player".equals(domain);
  }

  // ---------------------------------------------------------------- 手动设备 CRUD

  private static final Set<String> MANUAL_TYPES = Set.of(
      "light", "switch", "climate", "media_player", "sensor");

  @Override
  public IotDeviceVO addManualDevice(Long householdId, IotDeviceUpsertDTO dto) {
    String type = normalizeType(dto.getEntityType());
    boolean cloud = dto.getCloudDeviceId() != null && !dto.getCloudDeviceId().isBlank();
    String prefix = cloud ? "cloud." : "manual.";
    String entityId = prefix + type + "." + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    IotDeviceSnapshot snapshot = new IotDeviceSnapshot();
    snapshot.setHouseholdId(householdId);
    snapshot.setEntityId(entityId);
    snapshot.setEntityType(type);
    snapshot.setDeviceName(dto.getDeviceName().trim());
    snapshot.setBrand(dto.getBrand() == null || dto.getBrand().isBlank() ? null : dto.getBrand().trim());
    snapshot.setAreaName(dto.getAreaName() == null || dto.getAreaName().isBlank()
        ? "自定义" : dto.getAreaName().trim());
    snapshot.setState(dto.getState() == null || dto.getState().isBlank() ? "off" : dto.getState().trim());
    snapshot.setAttributes(attributesJson(dto.getAttributes()));
    if (cloud) {
      if (cloudClient == null || !cloudClient.isReady()) {
        throw new IllegalStateException("设备云未就绪：请配置涂鸦密钥（FAMILY_IOT_TUYA_*）或使用 provider=sim 联调");
      }
      snapshot.setDeviceSource("CLOUD");
      snapshot.setCloudDeviceId(dto.getCloudDeviceId().trim());
      snapshot.setCloudProvider(cloudClient.providerId());
    } else {
      snapshot.setDeviceSource("MANUAL");
    }
    snapshot.setIsOnline(true);
    deviceSnapshotMapper.insert(snapshot);
    log.info("家庭 {} 添加设备 {} ({}/{})", householdId, entityId, type, snapshot.getDeviceSource());
    return toDeviceVO(deviceSnapshotMapper.selectById(snapshot.getId()));
  }

  @Override
  public IotDeviceVO updateManualDevice(Long householdId, String entityId, IotDeviceUpsertDTO dto) {
    IotDeviceSnapshot snapshot = findManual(householdId, entityId);
    snapshot.setDeviceName(dto.getDeviceName().trim());
    snapshot.setBrand(dto.getBrand() == null || dto.getBrand().isBlank() ? null : dto.getBrand().trim());
    snapshot.setAreaName(dto.getAreaName() == null || dto.getAreaName().isBlank()
        ? "自定义" : dto.getAreaName().trim());
    // 类型不允许修改（命令白名单按类型校验，改类型会造成语义混乱）；如需换类型请删除后重建
    if (dto.getState() != null && !dto.getState().isBlank()) {
      snapshot.setState(dto.getState().trim());
    }
    if (dto.getAttributes() != null) {
      snapshot.setAttributes(attributesJson(dto.getAttributes()));
    }
    deviceSnapshotMapper.updateById(snapshot);
    return toDeviceVO(deviceSnapshotMapper.selectById(snapshot.getId()));
  }

  @Override
  public void deleteManualDevice(Long householdId, String entityId) {
    IotDeviceSnapshot snapshot = findUserManaged(householdId, entityId);
    deviceSnapshotMapper.deleteById(snapshot.getId());
    log.info("家庭 {} 删除设备 {} ({})", householdId, entityId, snapshot.getDeviceSource());
  }

  @Override
  public List<com.familyhome.iot.cloud.CloudDeviceInfo> listCloudDevices(Long householdId) throws Exception {
    if (cloudClient == null || !cloudClient.isReady()) {
      throw new IllegalStateException("设备云未就绪");
    }
    List<com.familyhome.iot.cloud.CloudDeviceInfo> remote = cloudClient.listDevices();
    // 已导入的打标记：前端可灰显
    QueryWrapper<IotDeviceSnapshot> qw = new QueryWrapper<>();
    qw.eq("household_id", householdId).eq("device_source", "CLOUD");
    java.util.Set<String> bound = deviceSnapshotMapper.selectList(qw).stream()
        .map(IotDeviceSnapshot::getCloudDeviceId)
        .filter(id -> id != null && !id.isBlank())
        .collect(java.util.stream.Collectors.toSet());
    for (com.familyhome.iot.cloud.CloudDeviceInfo info : remote) {
      // 复用 brand 字段旁路：attributes 没有，用 category 后缀不够干净；前端用 cloudDeviceId 比对即可
      if (bound.contains(info.getCloudDeviceId())) {
        info.setName((info.getName() == null ? info.getCloudDeviceId() : info.getName()) + "（已添加）");
      }
    }
    return remote;
  }

  @Override
  public IotDeviceVO importCloudDevice(Long householdId, String cloudDeviceId) throws Exception {
    if (cloudDeviceId == null || cloudDeviceId.isBlank()) {
      throw new IllegalArgumentException("云设备 ID 不能为空");
    }
    if (cloudClient == null || !cloudClient.isReady()) {
      throw new IllegalStateException("设备云未就绪");
    }
    QueryWrapper<IotDeviceSnapshot> exist = new QueryWrapper<>();
    exist.eq("household_id", householdId)
        .eq("cloud_device_id", cloudDeviceId.trim())
        .eq("device_source", "CLOUD");
    if (deviceSnapshotMapper.selectCount(exist) > 0) {
      throw new IllegalArgumentException("该云设备已添加");
    }
    com.familyhome.iot.cloud.CloudDeviceInfo matched = null;
    for (com.familyhome.iot.cloud.CloudDeviceInfo info : cloudClient.listDevices()) {
      if (cloudDeviceId.trim().equals(info.getCloudDeviceId())) {
        matched = info;
        break;
      }
    }
    IotDeviceUpsertDTO dto = new IotDeviceUpsertDTO();
    dto.setCloudDeviceId(cloudDeviceId.trim());
    if (matched != null) {
      dto.setDeviceName(matched.getName() == null ? cloudDeviceId.trim() : matched.getName());
      dto.setBrand(matched.getBrand());
      dto.setEntityType(matched.getEntityType() == null ? "switch" : matched.getEntityType());
      dto.setAreaName("家里");
    } else {
      dto.setDeviceName(cloudDeviceId.trim());
      dto.setEntityType("switch");
      dto.setAreaName("家里");
    }
    return addManualDevice(householdId, dto);
  }

  /** 只查用户自管设备（MANUAL / CLOUD），避免越权改/删中枢同步设备。 */
  private IotDeviceSnapshot findUserManaged(Long householdId, String entityId) {
    IotDeviceSnapshot snapshot = deviceSnapshotMapper.selectOne(
        new QueryWrapper<IotDeviceSnapshot>()
            .eq("household_id", householdId)
            .eq("entity_id", entityId)
            .in("device_source", "MANUAL", "CLOUD"));
    if (snapshot == null) {
      throw new IllegalArgumentException("设备不存在或不可编辑");
    }
    return snapshot;
  }

  private IotDeviceSnapshot findManual(Long householdId, String entityId) {
    return findUserManaged(householdId, entityId);
  }

  private String normalizeType(String raw) {
    if (raw == null || raw.isBlank()) throw new IllegalArgumentException("设备类型不能为空");
    String type = raw.trim().toLowerCase(java.util.Locale.ROOT);
    if (!MANUAL_TYPES.contains(type)) {
      throw new IllegalArgumentException("不支持的设备类型：" + raw);
    }
    return type;
  }

  private String attributesJson(JsonNode attributes) {
    if (attributes == null || attributes.isNull()) return null;
    if (!attributes.isObject()) throw new IllegalArgumentException("设备属性必须是 JSON 对象");
    return attributes.toString();
  }

  // ---------------------------------------------------------------- 设备响应探测

  @Override
  public DeviceTestResultVO testDevice(Long householdId, String entityId) {
    // 家庭隔离：只能探测本家庭名下的设备
    IotDeviceSnapshot snapshot = deviceSnapshotMapper.selectOne(
        new QueryWrapper<IotDeviceSnapshot>()
            .eq("household_id", householdId)
            .eq("entity_id", entityId));
    if (snapshot == null) {
      throw new IllegalArgumentException("设备不存在");
    }

    String name = snapshot.getDeviceName() != null ? snapshot.getDeviceName() : entityId;

    if ("MANUAL".equals(snapshot.getDeviceSource())) {
      return DeviceTestResultVO.simulated(name);
    }

    if ("CLOUD".equals(snapshot.getDeviceSource())) {
      if (cloudClient == null || !cloudClient.isReady()) {
        return DeviceTestResultVO.noResponse("设备云未就绪");
      }
      if ("sim".equals(cloudClient.providerId())) {
        return new DeviceTestResultVO(
            true, false, snapshot.getState(),
            "「" + name + "」走联调云通道（sim），未连真机。正式环境请配置 FAMILY_IOT_CLOUD_PROVIDER=tuya 与涂鸦密钥。");
      }
      return new DeviceTestResultVO(
          true, true, snapshot.getState(),
          "「" + name + "」已绑定涂鸦设备 " + snapshot.getCloudDeviceId() + "，指令经云端下发到家里 Wi‑Fi 设备。");
    }

    IotHub hub = hubService.getHubInternal(householdId);
    if (hub == null) {
      throw new IllegalStateException("设备通道未配置");
    }

    if (DemoHub.isDemo(hub.getBaseUrl()) || CloudHub.isCloud(hub.getBaseUrl())) {
      return DeviceTestResultVO.simulated(name);
    }

    // 真实中枢：实际读取该实体在 Home Assistant 上的实时状态
    try {
      JsonNode stateNode = homeAssistantClient.getState(hub, entityId);
      if (stateNode == null || stateNode.isNull()) {
        return DeviceTestResultVO.noResponse("Home Assistant 中不存在该实体，可能已被删除或改名");
      }
      JsonNode s = stateNode.get("state");
      String state = (s != null && !s.isNull()) ? s.asText() : null;
      return DeviceTestResultVO.responded(state);
    } catch (Exception e) {
      log.warn("探测设备响应失败: {} 家庭 {}", entityId, householdId, e);
      return DeviceTestResultVO.noResponse(e.getMessage() == null ? "无法连接中枢" : e.getMessage());
    }
  }
}
