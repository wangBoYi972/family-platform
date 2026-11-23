package com.familyhome.iot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.familyhome.iot.mapper.IotDeviceSnapshotMapper;
import com.familyhome.iot.model.IotDeviceSnapshot;
import com.familyhome.iot.model.IotHub;
import com.familyhome.iot.service.IotDeviceService;
import com.familyhome.iot.service.IotHubService;
import com.familyhome.iot.vo.AreaDevicesVO;
import com.familyhome.iot.vo.DeviceStateEventVO;
import com.familyhome.iot.vo.IotDeviceVO;
import com.familyhome.iot.client.HomeAssistantClient;
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
      throw new RuntimeException("中枢未配置");
    }

    if (DemoHub.isDemo(hub.getBaseUrl())) {
      seedDemoDevices(householdId);
      log.info("演示中枢：已为家庭 {} 生成示例设备", householdId);
      return;
    }

    try {
      JsonNode states = homeAssistantClient.getStates(hub);

      // 清空该家庭的旧快照
      QueryWrapper<IotDeviceSnapshot> qw = new QueryWrapper<>();
      qw.eq("household_id", householdId);
      deviceSnapshotMapper.delete(qw);

      // 插入新快照
      if (states.isArray()) {
        for (JsonNode state : states) {
          String entityId = state.get("entity_id").asText();
          String[] parts = entityId.split("\\.");
          if (parts.length != 2) continue;

          String domain = parts[0];

          // 过滤：只关心 light/switch/sensor/scene
          if (!isRelevantDomain(domain)) continue;

          IotDeviceSnapshot snapshot = new IotDeviceSnapshot();
          snapshot.setHouseholdId(householdId);
          snapshot.setEntityId(entityId);
          snapshot.setEntityType(domain);
          snapshot.setDeviceName(state.path("attributes").path("friendly_name").asText(entityId));
          snapshot.setAreaName(state.path("attributes").path("area_id").asText());
          snapshot.setState(state.get("state").asText());
          snapshot.setAttributes(
              state.has("attributes") && !state.get("attributes").isNull()
                  ? state.get("attributes").toString()
                  : null);
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
    // 批量标记所有设备离线，避免逐个更新的性能问题
    QueryWrapper<IotDeviceSnapshot> qw = new QueryWrapper<>();
    qw.eq("household_id", householdId);
    qw.eq("is_online", true);

    IotDeviceSnapshot update = new IotDeviceSnapshot();
    update.setIsOnline(false);

    deviceSnapshotMapper.update(update, qw);
    log.warn("家庭 {} 中枢已离线，所有设备标记为离线", householdId);
  }

  /**
   * 演示中枢的内置设备。
   *
   * <p>没有真实 Home Assistant 时，家庭成员依然能看到设备并操作，交互路径与真实中枢一致，
   * 只是状态保存在本地快照里。
   */
  private void seedDemoDevices(Long householdId) throws Exception {
    QueryWrapper<IotDeviceSnapshot> qw = new QueryWrapper<>();
    qw.eq("household_id", householdId);
    deviceSnapshotMapper.delete(qw);

    for (DemoDevice demo : DEMO_DEVICES) {
      IotDeviceSnapshot snapshot = new IotDeviceSnapshot();
      snapshot.setHouseholdId(householdId);
      snapshot.setEntityId(demo.entityId());
      snapshot.setEntityType(demo.entityId().substring(0, demo.entityId().indexOf('.')));
      snapshot.setDeviceName(demo.name());
      snapshot.setAreaName(demo.area());
      snapshot.setState(demo.state());
      snapshot.setAttributes(objectMapper.writeValueAsString(demo.attributes()));
      snapshot.setIsOnline(true);
      deviceSnapshotMapper.insert(snapshot);
    }
  }

  private record DemoDevice(
      String entityId, String name, String area, String state, Map<String, Object> attributes) {}

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
    vo.setEntityType(snapshot.getEntityType());
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
    return "light".equals(domain) || "switch".equals(domain) || "sensor".equals(domain) || "scene".equals(domain);
  }
}
