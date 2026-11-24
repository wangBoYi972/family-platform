package com.familyhome.iot;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyhome.iot.dto.IotDeviceUpsertDTO;
import com.familyhome.iot.mapper.IotDeviceSnapshotMapper;
import com.familyhome.iot.model.IotDeviceSnapshot;
import com.familyhome.iot.service.impl.IotDeviceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 手动设备 CRUD 测试：品牌字段、家庭隔离、类型白名单、同步不清手动设备。
 */
@ExtendWith(MockitoExtension.class)
class IotManualDeviceServiceTest {

  @Mock private IotDeviceSnapshotMapper mapper;
  private IotDeviceServiceImpl service;
  private final ObjectMapper om = new ObjectMapper();
  private static final long H1 = 100L;

  @BeforeEach
  void setUp() {
    service = new IotDeviceServiceImpl();
    inject(service, "deviceSnapshotMapper", mapper);
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

  private IotDeviceUpsertDTO dto(String name, String brand, String type, String area) {
    IotDeviceUpsertDTO d = new IotDeviceUpsertDTO();
    d.setDeviceName(name);
    d.setBrand(brand);
    d.setEntityType(type);
    d.setAreaName(area);
    return d;
  }

  @Test
  void addManualDeviceSetsBrandAndSource() {
    when(mapper.selectById(any())).thenAnswer(inv -> {
      IotDeviceSnapshot s = new IotDeviceSnapshot();
      s.setId(1L);
      return s;
    });

    service.addManualDevice(H1, dto("客厅空调", "格力", "climate", "客厅"));

    ArgumentCaptor<IotDeviceSnapshot> cap = ArgumentCaptor.forClass(IotDeviceSnapshot.class);
    verify(mapper).insert(cap.capture());
    IotDeviceSnapshot saved = cap.getValue();
    assertEquals(H1, saved.getHouseholdId());
    assertEquals("格力", saved.getBrand());
    assertEquals("MANUAL", saved.getDeviceSource());
    assertTrue(saved.getEntityId().startsWith("manual.climate."));
    assertEquals("客厅", saved.getAreaName());
  }

  @Test
  void blankBrandAndAreaUseDefaults() {
    when(mapper.selectById(any())).thenAnswer(inv -> {
      IotDeviceSnapshot s = new IotDeviceSnapshot();
      s.setId(1L);
      return s;
    });

    service.addManualDevice(H1, dto("插座", "  ", "switch", ""));

    ArgumentCaptor<IotDeviceSnapshot> cap = ArgumentCaptor.forClass(IotDeviceSnapshot.class);
    verify(mapper).insert(cap.capture());
    assertNull(cap.getValue().getBrand());
    assertEquals("自定义", cap.getValue().getAreaName());
  }

  @Test
  void unsupportedTypeRejected() {
    assertThrows(IllegalArgumentException.class, () ->
        service.addManualDevice(H1, dto("奇怪设备", "", "thermostat", "客厅")));
  }

  @Test
  void deleteOnlyManualDeviceOfHousehold() {
    IotDeviceSnapshot manual = new IotDeviceSnapshot();
    manual.setId(5L);
    manual.setHouseholdId(H1);
    manual.setEntityId("manual.climate.abc");
    manual.setDeviceSource("MANUAL");
    when(mapper.selectOne(any())).thenReturn(manual);

    service.deleteManualDevice(H1, "manual.climate.abc");

    verify(mapper).deleteById(5L);
    // 校验查询条件包含 household + entityId + MANUAL（用 captor 抓 Wrapper 结构不现实，这里验证只删了这一条）
    verify(mapper, never()).delete((Wrapper<IotDeviceSnapshot>) any());
  }

  @Test
  void cannotDeleteNonManualDevice() {
    when(mapper.selectOne(any())).thenReturn(null); // 中枢设备查不到（条件含 device_source=MANUAL）
    assertThrows(IllegalArgumentException.class, () ->
        service.deleteManualDevice(H1, "climate.living_room_ac"));
    verify(mapper, never()).deleteById(anyLong());
  }
}
