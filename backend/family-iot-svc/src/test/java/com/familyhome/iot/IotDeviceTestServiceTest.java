package com.familyhome.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyhome.iot.client.HomeAssistantClient;
import com.familyhome.iot.mapper.IotDeviceSnapshotMapper;
import com.familyhome.iot.model.IotDeviceSnapshot;
import com.familyhome.iot.model.IotHub;
import com.familyhome.iot.service.IotHubService;
import com.familyhome.iot.service.impl.IotDeviceServiceImpl;
import com.familyhome.iot.vo.DeviceTestResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 单设备响应探测测试：真实设备读到状态=响应、实体不存在=无响应、
 * 手动/演示设备=本地模拟、跨家庭设备拒绝、中枢不可达=无响应。
 */
@ExtendWith(MockitoExtension.class)
class IotDeviceTestServiceTest {

  @Mock private IotDeviceSnapshotMapper mapper;
  @Mock private IotHubService hubService;
  @Mock private HomeAssistantClient haClient;

  private IotDeviceServiceImpl service;
  private final ObjectMapper om = new ObjectMapper();
  private static final long H1 = 100L;

  @BeforeEach
  void setUp() {
    service = new IotDeviceServiceImpl();
    inject(service, "deviceSnapshotMapper", mapper);
    inject(service, "hubService", hubService);
    inject(service, "homeAssistantClient", haClient);
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

  private IotDeviceSnapshot device(String entityId, String name, String source) {
    IotDeviceSnapshot s = new IotDeviceSnapshot();
    s.setHouseholdId(H1);
    s.setEntityId(entityId);
    s.setDeviceName(name);
    s.setDeviceSource(source);
    return s;
  }

  private IotHub realHub() {
    IotHub hub = new IotHub();
    hub.setBaseUrl("http://192.168.1.100:8123");
    hub.setAccessToken("token");
    return hub;
  }

  @Test
  void realDeviceRespondedWithState() throws Exception {
    when(mapper.selectOne(any())).thenReturn(device("climate.ac", "客厅空调", "HUB"));
    when(hubService.getHubInternal(H1)).thenReturn(realHub());
    when(haClient.getState(any(), eq("climate.ac")))
        .thenReturn(om.readTree("{\"entity_id\":\"climate.ac\",\"state\":\"cool\"}"));

    DeviceTestResultVO res = service.testDevice(H1, "climate.ac");

    assertTrue(res.isOk());
    assertTrue(res.isRealHardware());
    assertEquals("cool", res.getState());
    assertTrue(res.getMessage().contains("响应正常"));
  }

  @Test
  void realDeviceMissingEntityIsNoResponse() throws Exception {
    when(mapper.selectOne(any())).thenReturn(device("switch.missing", "缺失插座", "HUB"));
    when(hubService.getHubInternal(H1)).thenReturn(realHub());
    when(haClient.getState(any(), eq("switch.missing"))).thenReturn(null);

    DeviceTestResultVO res = service.testDevice(H1, "switch.missing");

    assertFalse(res.isOk());
    assertTrue(res.isRealHardware());
    assertNull(res.getState());
    assertTrue(res.getMessage().contains("不存在"));
  }

  @Test
  void realDeviceHubUnreachableIsNoResponse() throws Exception {
    when(mapper.selectOne(any())).thenReturn(device("light.x", "灯", "HUB"));
    when(hubService.getHubInternal(H1)).thenReturn(realHub());
    when(haClient.getState(any(), eq("light.x"))).thenThrow(new RuntimeException("连接超时"));

    DeviceTestResultVO res = service.testDevice(H1, "light.x");

    assertFalse(res.isOk());
    assertTrue(res.isRealHardware());
    assertTrue(res.getMessage().contains("连接超时"));
  }

  @Test
  void manualDeviceIsSimulatedNoHaCall() throws Exception {
    when(mapper.selectOne(any())).thenReturn(device("manual.climate.abc", "我的空调", "MANUAL"));

    DeviceTestResultVO res = service.testDevice(H1, "manual.climate.abc");

    assertTrue(res.isOk());
    assertFalse(res.isRealHardware());
    assertNull(res.getState());
    assertTrue(res.getMessage().contains("本地模拟"));
    verifyNoInteractions(hubService, haClient);
  }

  @Test
  void demoHubDeviceIsSimulated() throws Exception {
    when(mapper.selectOne(any())).thenReturn(device("climate.demo", "演示空调", "HUB"));
    IotHub demo = new IotHub();
    demo.setBaseUrl("demo://local");
    demo.setAccessToken("demo");
    when(hubService.getHubInternal(H1)).thenReturn(demo);

    DeviceTestResultVO res = service.testDevice(H1, "climate.demo");

    assertTrue(res.isOk());
    assertFalse(res.isRealHardware());
    assertTrue(res.getMessage().contains("本地模拟"));
    verifyNoInteractions(haClient);
  }

  @Test
  void deviceNotFoundRejected() {
    when(mapper.selectOne(any())).thenReturn(null);
    assertThrows(IllegalArgumentException.class, () -> service.testDevice(H1, "climate.other"));
    verifyNoInteractions(hubService, haClient);
  }

  @Test
  void noHubConfiguredRejected() {
    when(mapper.selectOne(any())).thenReturn(device("climate.ac", "客厅空调", "HUB"));
    when(hubService.getHubInternal(H1)).thenReturn(null);
    assertThrows(IllegalStateException.class, () -> service.testDevice(H1, "climate.ac"));
  }
}
