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
import com.familyhome.iot.util.DemoHub;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 命令执行服务实现
 */
@Slf4j
@Service
public class IotCommandServiceImpl implements IotCommandService {

  // 白名单：灯/插座/空调/电视音响/场景。越权命令直接拒。
  private static final Set<String> ALLOWED_COMMANDS = Set.of(
      "light.turn_on",
      "light.turn_off",
      "switch.turn_on",
      "switch.turn_off",
      "scene.turn_on",
      "climate.turn_on",
      "climate.turn_off",
      "climate.set_temperature",
      "climate.set_hvac_mode",
      "climate.set_fan_mode",
      "media_player.turn_on",
      "media_player.turn_off",
      "media_player.volume_set",
      "media_player.volume_mute",
      "media_player.media_play_pause",
      "media_player.select_source"
  );

  private static final Set<String> HVAC_MODES =
      Set.of("off", "cool", "heat", "dry", "fan_only", "auto");
  private static final Set<String> FAN_MODES = Set.of("auto", "low", "medium", "high");

  @Autowired
  private IotHubService hubService;

  @Autowired
  private IotCommandLogMapper commandLogMapper;

  @Autowired
  private IotDeviceSnapshotMapper deviceSnapshotMapper;

  @Autowired
  private HomeAssistantClient homeAssistantClient;

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

    // 获取中枢配置
    IotHub hub = hubService.getHubInternal(householdId);
    if (hub == null) {
      throw new RuntimeException("中枢未配置");
    }
    boolean demo = DemoHub.isDemo(hub.getBaseUrl());

    // 构建命令日志记录
    IotCommandLog cmdLog = new IotCommandLog();
    cmdLog.setHouseholdId(householdId);
    cmdLog.setUserId(userId);
    cmdLog.setEntityId(entityId);
    cmdLog.setCommandType(dto.getCommandType());
    cmdLog.setCommandDomain(device.getEntityType());
    cmdLog.setCommandService(dto.getCommandType());

    try {
      // 准备服务参数
      // 注意：前端不传参数时 JSON body 里是 null，Jackson 会映射成 NullNode 而不是 null，
      // 直接强转 ObjectNode 会抛 ClassCastException（开关类命令必踩），所以先判 isNull。
      JsonNode parameters = dto.getParameters();
      if (parameters == null || parameters.isNull()) {
        parameters = objectMapper.createObjectNode();
      }
      if (!parameters.isObject()) {
        throw new IllegalArgumentException("命令参数格式不正确");
      }
      ((com.fasterxml.jackson.databind.node.ObjectNode) parameters).put("entity_id", entityId);
      cmdLog.setRequestData(parameters);

      // 调用 Home Assistant（演示中枢只改本地状态）
      boolean success = demo
          ? applyDemoCommand(device, dto.getCommandType(), parameters)
          : homeAssistantClient.callService(hub, device.getEntityType(), dto.getCommandType(), parameters);
      cmdLog.setSuccess(success);

      if (!success) {
        cmdLog.setErrorMessage(demo ? "演示设备不支持该操作" : "Home Assistant 返回失败状态");
      }
    } catch (Exception e) {
      cmdLog.setSuccess(false);
      cmdLog.setErrorMessage(e.getMessage());
      throw e;
    } finally {
      // 记录命令日志（成功和失败都记）
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
   * 演示中枢：本地改快照状态，模拟一次成功的服务调用（含空调/电视/音响）。
   */
  @SuppressWarnings("unchecked")
  private boolean applyDemoCommand(IotDeviceSnapshot device, String commandType, JsonNode parameters)
      throws Exception {
    Map<String, Object> attributes = new HashMap<>();
    if (device.getAttributes() != null && !device.getAttributes().isBlank()) {
      attributes.putAll(objectMapper.readValue(device.getAttributes(), Map.class));
    }

    String type = device.getEntityType();
    String state = device.getState();

    switch (commandType) {
      case "turn_on" -> {
        if ("climate".equals(type)) {
          Object previous = attributes.getOrDefault("hvac_mode", "cool");
          String mode = String.valueOf(previous);
          if ("off".equals(mode) || mode.isBlank()) mode = "cool";
          state = mode;
          attributes.put("hvac_mode", mode);
        } else if ("media_player".equals(type)) {
          state = "playing";
        } else {
          state = "on";
          if ("light".equals(type) && parameters.hasNonNull("brightness")) {
            attributes.put("brightness", parameters.get("brightness").asInt());
          }
        }
      }
      case "turn_off" -> state = "off";
      case "set_temperature" -> {
        if (!parameters.hasNonNull("temperature")) {
          throw new IllegalArgumentException("缺少目标温度");
        }
        int temp = parameters.get("temperature").asInt();
        if (temp < 16 || temp > 35) {
          throw new IllegalArgumentException("温度需在 16–35°C");
        }
        attributes.put("temperature", temp);
        if ("off".equals(state)) {
          state = "cool";
          attributes.put("hvac_mode", "cool");
        }
      }
      case "set_hvac_mode" -> {
        if (!parameters.hasNonNull("hvac_mode")) {
          throw new IllegalArgumentException("缺少运行模式");
        }
        String mode = parameters.get("hvac_mode").asText();
        if (!HVAC_MODES.contains(mode)) {
          throw new IllegalArgumentException("不支持的空调模式: " + mode);
        }
        attributes.put("hvac_mode", mode);
        state = "off".equals(mode) ? "off" : mode;
      }
      case "set_fan_mode" -> {
        if (!parameters.hasNonNull("fan_mode")) {
          throw new IllegalArgumentException("缺少风速");
        }
        String fan = parameters.get("fan_mode").asText();
        if (!FAN_MODES.contains(fan)) {
          throw new IllegalArgumentException("不支持的风速: " + fan);
        }
        attributes.put("fan_mode", fan);
      }
      case "volume_set" -> {
        if (!parameters.hasNonNull("volume_level")) {
          throw new IllegalArgumentException("缺少音量");
        }
        double volume = parameters.get("volume_level").asDouble();
        if (volume < 0 || volume > 1) {
          throw new IllegalArgumentException("音量需在 0–1");
        }
        attributes.put("volume_level", volume);
        attributes.put("is_volume_muted", false);
      }
      case "volume_mute" -> {
        if (!parameters.hasNonNull("is_volume_muted")) {
          throw new IllegalArgumentException("缺少静音参数");
        }
        attributes.put("is_volume_muted", parameters.get("is_volume_muted").asBoolean());
      }
      case "media_play_pause" -> {
        state = "playing".equals(state) ? "paused" : "playing";
      }
      case "select_source" -> {
        if (!parameters.hasNonNull("source")) {
          throw new IllegalArgumentException("缺少音源/频道");
        }
        attributes.put("source", parameters.get("source").asText());
        if ("off".equals(state) || "idle".equals(state) || "standby".equals(state)) {
          state = "playing";
        }
      }
      default -> {
        return false;
      }
    }

    deviceService.updateDeviceState(
        device.getHouseholdId(), device.getEntityId(), state, attributes.isEmpty() ? null : attributes);
    return true;
  }

  private boolean isCommandAllowed(String command) {
    return ALLOWED_COMMANDS.contains(command);
  }
}
