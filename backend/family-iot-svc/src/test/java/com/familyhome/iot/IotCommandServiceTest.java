package com.familyhome.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyhome.iot.client.HomeAssistantClient;
import com.familyhome.iot.dto.IotCommandDTO;
import com.familyhome.iot.mapper.IotCommandLogMapper;
import com.familyhome.iot.mapper.IotDeviceSnapshotMapper;
import com.familyhome.iot.model.IotDeviceSnapshot;
import com.familyhome.iot.model.IotHub;
import com.familyhome.iot.service.IotDeviceService;
import com.familyhome.iot.service.IotHubService;
import com.familyhome.iot.service.impl.IotCommandServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 设备命令白名单与本地模拟状态更新测试
 *
 * <p>覆盖空调温度/模式/风速、电视音量/频道、灯光亮度、非法命令拒绝。
 */
@ExtendWith(MockitoExtension.class)
class IotCommandServiceTest {

  @Mock private IotHubService hubService;
  @Mock private IotCommandLogMapper commandLogMapper;
  @Mock private IotDeviceSnapshotMapper deviceSnapshotMapper;
  @Mock private HomeAssistantClient homeAssistantClient;
  @Mock private IotDeviceService deviceService;

  private IotCommandServiceImpl service;
  private final ObjectMapper om = new ObjectMapper();
  private static final long HOUSEHOLD = 100L;

  @BeforeEach
  void setUp() {
    service = new IotCommandServiceImpl();
    // 手工注入（测试中不用 Spring 容器）
    inject(service, "hubService", hubService);
    inject(service, "commandLogMapper", commandLogMapper);
    inject(service, "deviceSnapshotMapper", deviceSnapshotMapper);
    inject(service, "homeAssistantClient", homeAssistantClient);
    inject(service, "deviceService", deviceService);
  }

  private static void inject(Object target, String field, Object value) {
    try {
      var f = target.getClass().getDeclaredField(field);
      f.setAccessible(true);
      f.set(target, value);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** 构造一个手动设备（无需中枢） */
  private IotDeviceSnapshot device(String type, String entityId, String state, Map<String, Object> attrs) throws Exception {
    IotDeviceSnapshot d = new IotDeviceSnapshot();
    d.setHouseholdId(HOUSEHOLD);
    d.setEntityId(entityId);
    d.setEntityType(type);
    d.setState(state);
    d.setDeviceSource("MANUAL");
    d.setAttributes(attrs == null ? null : om.writeValueAsString(attrs));
    when(deviceSnapshotMapper.selectOne(any())).thenReturn(d);
    return d;
  }

  private IotCommandDTO cmd(String type, Map<String, Object> params) throws Exception {
    IotCommandDTO dto = new IotCommandDTO();
    dto.setCommandType(type);
    if (params != null) dto.setParameters(om.readTree(om.writeValueAsString(params)));
    return dto;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> capturedAttributes() {
    ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
    verify(deviceService, atLeastOnce()).updateDeviceState(anyLong(), anyString(), anyString(), cap.capture());
    return cap.getValue();
  }

  private String capturedState() {
    ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
    verify(deviceService, atLeastOnce()).updateDeviceState(anyLong(), anyString(), cap.capture(), any());
    return cap.getValue();
  }

  @Test
  void manualDeviceNeedsNoHub() throws Exception {
    device("climate", "manual.climate.abc", "cool", Map.of("temperature", 26));
    service.executeCommand(HOUSEHOLD, 1L, "manual.climate.abc", cmd("set_temperature", Map.of("temperature", 24)));
    verifyNoInteractions(hubService); // 手动设备完全不碰中枢
    assertEquals(24, ((Number) capturedAttributes().get("temperature")).intValue());
  }

  @Test
  void climateTemperatureModeFan() throws Exception {
    device("climate", "manual.climate.abc", "cool",
        Map.of("temperature", 26, "hvac_mode", "cool", "fan_mode", "auto"));

    service.executeCommand(HOUSEHOLD, 1L, "manual.climate.abc", cmd("set_temperature", Map.of("temperature", 22)));
    assertEquals(22, ((Number) capturedAttributes().get("temperature")).intValue());

    service.executeCommand(HOUSEHOLD, 1L, "manual.climate.abc", cmd("set_fan_mode", Map.of("fan_mode", "high")));
    assertEquals("high", capturedAttributes().get("fan_mode"));

    service.executeCommand(HOUSEHOLD, 1L, "manual.climate.abc", cmd("set_hvac_mode", Map.of("hvac_mode", "heat")));
    assertEquals("heat", capturedState());
  }

  @Test
  void climateInvalidValuesRejected() throws Exception {
    IotDeviceSnapshot d = device("climate", "manual.climate.abc", "cool", Map.of());
    assertThrows(IllegalArgumentException.class, () ->
        service.executeCommand(HOUSEHOLD, 1L, d.getEntityId(), cmd("set_temperature", Map.of("temperature", 99))));
    assertThrows(IllegalArgumentException.class, () ->
        service.executeCommand(HOUSEHOLD, 1L, d.getEntityId(), cmd("set_hvac_mode", Map.of("hvac_mode", "nuclear"))));
  }

  @Test
  void tvVolumeMuteAndSource() throws Exception {
    device("media_player", "manual.media_player.tv", "off",
        Map.of("volume_level", 0.2, "source", "HDMI 1"));

    service.executeCommand(HOUSEHOLD, 1L, "manual.media_player.tv", cmd("volume_set", Map.of("volume_level", 0.7)));
    assertEquals(0.7, ((Number) capturedAttributes().get("volume_level")).doubleValue(), 0.001);

    service.executeCommand(HOUSEHOLD, 1L, "manual.media_player.tv", cmd("select_source", Map.of("source", "TV")));
    assertEquals("TV", capturedAttributes().get("source"));

    service.executeCommand(HOUSEHOLD, 1L, "manual.media_player.tv", cmd("volume_mute", Map.of("is_volume_muted", true)));
    assertEquals(true, capturedAttributes().get("is_volume_muted"));
  }

  @Test
  void lightBrightnessStillWorks() throws Exception {
    device("light", "manual.light.abc", "off", Map.of("brightness", 100));
    service.executeCommand(HOUSEHOLD, 1L, "manual.light.abc",
        cmd("turn_on", Map.of("brightness", 200)));
    assertEquals(200, ((Number) capturedAttributes().get("brightness")).intValue());
    assertEquals("on", capturedState());
  }

  @Test
  void unsupportedCommandRejected() throws Exception {
    IotDeviceSnapshot d = device("switch", "manual.switch.abc", "off", Map.of());
    assertThrows(IllegalArgumentException.class, () ->
        service.executeCommand(HOUSEHOLD, 1L, d.getEntityId(), cmd("set_temperature", Map.of("temperature", 24))));
  }

  @Test
  void nonExistentDeviceRejected() throws Exception {
    when(deviceSnapshotMapper.selectOne(any())).thenReturn(null);
    assertThrows(IllegalArgumentException.class, () ->
        service.executeCommand(HOUSEHOLD, 1L, "missing", cmd("turn_on", null)));
  }
}
