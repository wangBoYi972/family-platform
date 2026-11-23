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

  // 白名单：只允许这些命令
  private static final String[] ALLOWED_COMMANDS = {
      "light.turn_on",
      "light.turn_off",
      "switch.turn_on",
      "switch.turn_off",
      "scene.turn_on"
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
   * 演示中枢：本地翻转设备状态，模拟一次成功的服务调用。
   */
  @SuppressWarnings("unchecked")
  private boolean applyDemoCommand(IotDeviceSnapshot device, String commandType, JsonNode parameters)
      throws Exception {
    String state;
    if ("turn_on".equals(commandType)) {
      state = "on";
    } else if ("turn_off".equals(commandType)) {
      state = "off";
    } else {
      return false;
    }

    Map<String, Object> attributes = new HashMap<>();
    if (device.getAttributes() != null && !device.getAttributes().isBlank()) {
      attributes.putAll(objectMapper.readValue(device.getAttributes(), Map.class));
    }
    if ("light".equals(device.getEntityType()) && "on".equals(state) && parameters.hasNonNull("brightness")) {
      attributes.put("brightness", parameters.get("brightness").asInt());
    }

    deviceService.updateDeviceState(
        device.getHouseholdId(), device.getEntityId(), state, attributes.isEmpty() ? null : attributes);
    return true;
  }

  private boolean isCommandAllowed(String command) {
    // 使用 Set 而非数组，提高查询效率
    final Set<String> allowed = new HashSet<>(Arrays.asList(ALLOWED_COMMANDS));
    return allowed.contains(command);
  }
}
