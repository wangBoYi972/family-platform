package com.familyhome.iot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyhome.iot.dto.IotCommandDTO;
import com.familyhome.iot.mapper.IotCommandLogMapper;
import com.familyhome.iot.mapper.IotDeviceSnapshotMapper;
import com.familyhome.iot.model.IotCommandLog;
import com.familyhome.iot.model.IotDeviceSnapshot;
import com.familyhome.iot.model.IotHub;
import com.familyhome.iot.service.IotCommandService;
import com.familyhome.iot.service.IotDeviceService;
import com.familyhome.iot.service.IotHubService;
import com.familyhome.iot.client.HomeAssistantClient;
import com.familyhome.iot.cloud.IotCloudClient;
import com.familyhome.iot.util.CloudHub;
import com.familyhome.iot.util.DemoHub;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 命令执行服务实现
 */
@Slf4j
@Service
public class IotCommandServiceImpl implements IotCommandService {

  // 白名单：只允许这些命令。命名与 Home Assistant domain.service 一致，
  // 空调=climate，电视/音响=media_player。
  private static final String[] ALLOWED_COMMANDS = {
      "light.turn_on", "light.turn_off",
      "switch.turn_on", "switch.turn_off",
      "scene.turn_on",
      // 空调：开关 / 设定温度 / 运行模式 / 风速
      "climate.turn_on", "climate.turn_off",
      "climate.set_temperature", "climate.set_hvac_mode", "climate.set_fan_mode",
      // 电视/音响：开关 / 音量 / 静音 / 频道(音源) / 播放控制
      "media_player.turn_on", "media_player.turn_off",
      "media_player.volume_set", "media_player.volume_mute",
      "media_player.select_source", "media_player.media_play",
      "media_player.media_pause", "media_player.media_next_track",
      "media_player.media_previous_track"
  };

  @Autowired
  private IotHubService hubService;

  @Autowired
  private IotCommandLogMapper commandLogMapper;

  @Autowired
  private IotDeviceSnapshotMapper deviceSnapshotMapper;

  @Autowired
  private HomeAssistantClient homeAssistantClient;

  @Autowired
  private IotCloudClient cloudClient;

  @Autowired
  private IotDeviceService deviceService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public void executeCommand(Long householdId, Long userId, String entityId, IotCommandDTO dto) throws Exception {
    IotDeviceSnapshot device = deviceSnapshotMapper.selectOne(
        new QueryWrapper<IotDeviceSnapshot>()
            .eq("household_id", householdId)
            .eq("entity_id", entityId)
    );

    if (device == null) {
      throw new IllegalArgumentException("设备不存在");
    }

    // 验证命令白名单
    String commandKey = device.getEntityType() + "." + dto.getCommandType();
    if (!isCommandAllowed(commandKey)) {
      log.warn("非白名单命令被拒绝: {} 在家庭 {}", commandKey, householdId);
      throw new IllegalArgumentException("不支持的命令");
    }

    boolean manual = "MANUAL".equals(device.getDeviceSource());
    boolean cloud = "CLOUD".equals(device.getDeviceSource());
    boolean demo = false;
    IotHub hub = null;
    if (!manual && !cloud) {
      hub = hubService.getHubInternal(householdId);
      if (hub == null) {
        throw new RuntimeException("设备通道未配置");
      }
      demo = DemoHub.isDemo(hub.getBaseUrl());
      if (CloudHub.isCloud(hub.getBaseUrl())) {
        // 云通道下不应再有 HUB 源设备走 HA
        throw new IllegalStateException("请将设备按云设备添加（填写涂鸦设备 ID）");
      }
    }

    IotCommandLog cmdLog = new IotCommandLog();
    cmdLog.setHouseholdId(householdId);
    cmdLog.setUserId(userId);
    cmdLog.setEntityId(entityId);
    cmdLog.setCommandType(dto.getCommandType());
    cmdLog.setCommandDomain(device.getEntityType());
    cmdLog.setCommandService(dto.getCommandType());

    try {
      JsonNode parameters = dto.getParameters();
      if (parameters == null || parameters.isNull()) {
        parameters = objectMapper.createObjectNode();
      }
      if (!parameters.isObject()) {
        throw new IllegalArgumentException("命令参数格式不正确");
      }
      ((com.fasterxml.jackson.databind.node.ObjectNode) parameters).put("entity_id", entityId);
      cmdLog.setRequestData(parameters);

      boolean success;
      if (cloud) {
        if (device.getCloudDeviceId() == null || device.getCloudDeviceId().isBlank()) {
          throw new IllegalStateException("云设备缺少 cloudDeviceId");
        }
        success = cloudClient.sendCommand(
            device.getCloudDeviceId(), device.getEntityType(), dto.getCommandType(), parameters);
        if (success) {
          // 乐观更新本地快照，便于 App 立刻反馈
          applyDemoCommand(device, dto.getCommandType(), parameters);
        }
      } else if (demo || manual) {
        success = applyDemoCommand(device, dto.getCommandType(), parameters);
      } else {
        success = homeAssistantClient.callService(
            hub, device.getEntityType(), dto.getCommandType(), parameters);
      }
      cmdLog.setSuccess(success);

      if (!success) {
        String reason = cloud ? "云端设备未响应"
            : (demo || manual) ? "设备不支持该操作或参数不合法"
            : "Home Assistant 返回失败状态";
        cmdLog.setErrorMessage(reason);
        throw new IllegalArgumentException(reason);
      }
    } catch (Exception e) {
      cmdLog.setSuccess(false);
      cmdLog.setErrorMessage(e.getMessage());
      throw e;
    } finally {
      commandLogMapper.insert(cmdLog);
    }
  }

  @Override
  public void activateScene(Long householdId, Long userId, String sceneEntityId) throws Exception {
    IotDeviceSnapshot scene = deviceSnapshotMapper.selectOne(
        new QueryWrapper<IotDeviceSnapshot>()
            .eq("household_id", householdId)
            .eq("entity_id", sceneEntityId)
            .eq("entity_type", "scene")
    );

    if (scene == null) {
      throw new IllegalArgumentException("场景不存在");
    }

    // 获取中枢配置
    IotHub hub = hubService.getHubInternal(householdId);
    if (hub == null) {
      throw new RuntimeException("中枢未配置");
    }
    boolean demo = DemoHub.isDemo(hub.getBaseUrl());

    IotCommandLog cmdLog = new IotCommandLog();
    cmdLog.setHouseholdId(householdId);
    cmdLog.setUserId(userId);
    cmdLog.setEntityId(sceneEntityId);
    cmdLog.setCommandType("turn_on");
    cmdLog.setCommandDomain("scene");
    cmdLog.setCommandService("turn_on");

    try {
      JsonNode parameters = objectMapper.createObjectNode();
      ((com.fasterxml.jackson.databind.node.ObjectNode) parameters).put("entity_id", sceneEntityId);
      cmdLog.setRequestData(parameters);

      boolean success = demo || homeAssistantClient.callService(hub, "scene", "turn_on", parameters);
      cmdLog.setSuccess(success);

      if (!success) {
        cmdLog.setErrorMessage("Home Assistant 返回失败状态");
      }
    } catch (Exception e) {
      cmdLog.setSuccess(false);
      cmdLog.setErrorMessage(e.getMessage());
      throw e;
    } finally {
      commandLogMapper.insert(cmdLog);
    }
  }

  /**
   * 本地模拟命令：演示中枢和手动设备都走这里，模拟一次成功的服务调用并更新快照状态。
   *
   * <p>支持开关、灯光亮度、空调温度/模式/风速、电视音响音量/静音/频道/播放控制。
   * 状态字段约定：普通设备 state=on/off；空调 state=hvac_mode（cool/heat/off...）。
   */
  @SuppressWarnings("unchecked")
  private boolean applyDemoCommand(IotDeviceSnapshot device, String commandType, JsonNode parameters)
      throws Exception {
    String type = device.getEntityType();
    Map<String, Object> attributes = new HashMap<>();
    if (device.getAttributes() != null && !device.getAttributes().isBlank()) {
      attributes.putAll(objectMapper.readValue(device.getAttributes(), Map.class));
    }
    String state = device.getState();

    switch (commandType) {
      case "turn_on" -> {
        state = "on";
        // 空调用模式作为 state，开启时恢复上次模式或默认制冷
        if ("climate".equals(type)) {
          state = str(attributes.get("hvac_mode"), "cool");
          if ("off".equals(state)) state = "cool";
        }
        // 灯光亮度随 turn_on 一起下发（前端拖动亮度条就是这种）
        if ("light".equals(type) && parameters.hasNonNull("brightness")) {
          attributes.put("brightness", parameters.get("brightness").asInt());
        }
      }
      case "turn_off" -> {
        state = "off";
        if ("climate".equals(type)) attributes.put("hvac_mode", "off");
      }
      case "set_temperature" -> {
        if (!parameters.hasNonNull("temperature")) return false;
        double t = parameters.get("temperature").asDouble();
        if (t < 16 || t > 35) return false; // 合理温度区间，防止误操作/越界
        attributes.put("temperature", t);
        // 调温即视为开机
        if ("off".equals(state)) state = str(attributes.get("hvac_mode"), "cool");
      }
      case "set_hvac_mode" -> {
        if (!parameters.hasNonNull("hvac_mode")) return false;
        String mode = parameters.get("hvac_mode").asText();
        if (!java.util.Set.of("cool", "heat", "dry", "fan_only", "auto", "off").contains(mode)) return false;
        attributes.put("hvac_mode", mode);
        state = mode;
      }
      case "set_fan_mode" -> {
        if (!parameters.hasNonNull("fan_mode")) return false;
        String fan = parameters.get("fan_mode").asText();
        if (!java.util.Set.of("auto", "low", "mid", "high").contains(fan)) return false;
        attributes.put("fan_mode", fan);
      }
      case "volume_set" -> {
        if (!parameters.hasNonNull("volume_level")) return false;
        double v = parameters.get("volume_level").asDouble();
        if (v < 0 || v > 1) return false;
        attributes.put("volume_level", v);
      }
      case "volume_mute" -> {
        if (!parameters.hasNonNull("is_volume_muted")) return false;
        attributes.put("is_volume_muted", parameters.get("is_volume_muted").asBoolean());
      }
      case "select_source" -> {
        if (!parameters.hasNonNull("source")) return false;
        attributes.put("source", parameters.get("source").asText());
      }
      case "media_play" -> state = "on";
      case "media_pause" -> state = "paused";
      case "media_next_track", "media_previous_track" -> {
        // 无真实曲目源，仅在标题上留痕，避免看起来"点了没反应"
        String title = str(attributes.get("media_title"), "");
        attributes.put("media_title", "media_next_track".equals(commandType)
            ? (title.isEmpty() ? "下一曲" : title + " ›") : "上一曲");
      }
      default -> {
        // 灯光亮度走这里（turn_on 已处理，这里兼容历史前端直接发 set brightness 的调用）
        if ("light".equals(type) && parameters.hasNonNull("brightness")) {
          attributes.put("brightness", parameters.get("brightness").asInt());
          state = "on";
        } else {
          return false;
        }
      }
    }

    deviceService.updateDeviceState(device.getHouseholdId(), device.getEntityId(), state, attributes);
    return true;
  }

  private static String str(Object value, String fallback) {
    return value == null ? fallback : String.valueOf(value);
  }

  private boolean isCommandAllowed(String command) {
    // 使用 Set 而非数组，提高查询效率
    final Set<String> allowed = new HashSet<>(Arrays.asList(ALLOWED_COMMANDS));
    return allowed.contains(command);
  }
}
