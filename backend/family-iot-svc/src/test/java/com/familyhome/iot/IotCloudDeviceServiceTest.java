package com.familyhome.iot;

import com.familyhome.iot.cloud.CloudDeviceInfo;
import com.familyhome.iot.cloud.IotCloudClient;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IotCloudDeviceServiceTest {

  @Mock private IotDeviceSnapshotMapper mapper;
  @Mock private IotCloudClient cloudClient;
  private IotDeviceServiceImpl service;
  private static final long H1 = 200L;

  @BeforeEach
  void setUp() {
    service = new IotDeviceServiceImpl();
    inject(service, "deviceSnapshotMapper", mapper);
    inject(service, "cloudClient", cloudClient);
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

  @Test
  void addWithCloudDeviceIdCreatesCloudSource() {
    when(cloudClient.isReady()).thenReturn(true);
    when(cloudClient.providerId()).thenReturn("sim");
    when(mapper.selectById(any())).thenAnswer(inv -> {
      IotDeviceSnapshot s = new IotDeviceSnapshot();
      s.setId(9L);
      s.setDeviceSource("CLOUD");
      return s;
    });

    IotDeviceUpsertDTO dto = new IotDeviceUpsertDTO();
    dto.setDeviceName("客厅空调");
    dto.setEntityType("climate");
    dto.setCloudDeviceId("sim-ac-living");
    dto.setAreaName("客厅");

    service.addManualDevice(H1, dto);

    ArgumentCaptor<IotDeviceSnapshot> cap = ArgumentCaptor.forClass(IotDeviceSnapshot.class);
    verify(mapper).insert(cap.capture());
    IotDeviceSnapshot saved = cap.getValue();
    assertEquals("CLOUD", saved.getDeviceSource());
    assertEquals("sim-ac-living", saved.getCloudDeviceId());
    assertEquals("sim", saved.getCloudProvider());
    assertTrue(saved.getEntityId().startsWith("cloud.climate."));
  }

  @Test
  void importCloudDeviceUsesRemoteMeta() throws Exception {
    when(cloudClient.isReady()).thenReturn(true);
    when(cloudClient.providerId()).thenReturn("sim");
    when(mapper.selectCount(any())).thenReturn(0L);
    CloudDeviceInfo info = new CloudDeviceInfo();
    info.setCloudDeviceId("sim-tv-living");
    info.setName("客厅电视");
    info.setBrand("小米");
    info.setEntityType("media_player");
    when(cloudClient.listDevices()).thenReturn(List.of(info));
    when(mapper.selectById(any())).thenAnswer(inv -> {
      IotDeviceSnapshot s = new IotDeviceSnapshot();
      s.setId(3L);
      return s;
    });

    service.importCloudDevice(H1, "sim-tv-living");

    ArgumentCaptor<IotDeviceSnapshot> cap = ArgumentCaptor.forClass(IotDeviceSnapshot.class);
    verify(mapper).insert(cap.capture());
    assertEquals("CLOUD", cap.getValue().getDeviceSource());
    assertEquals("客厅电视", cap.getValue().getDeviceName());
    assertEquals("media_player", cap.getValue().getEntityType());
  }

  @Test
  void cloudAddFailsWhenCloudNotReady() {
    when(cloudClient.isReady()).thenReturn(false);
    IotDeviceUpsertDTO dto = new IotDeviceUpsertDTO();
    dto.setDeviceName("空调");
    dto.setEntityType("climate");
    dto.setCloudDeviceId("x");
    assertThrows(IllegalStateException.class, () -> service.addManualDevice(H1, dto));
  }

  @Test
  void manualAddWithSameCloudIdRejected() {
    when(cloudClient.isReady()).thenReturn(true);
    when(mapper.selectCount(any())).thenReturn(1L);
    IotDeviceUpsertDTO dto = new IotDeviceUpsertDTO();
    dto.setDeviceName("重复空调");
    dto.setEntityType("climate");
    dto.setCloudDeviceId("sim-ac-living");
    assertThrows(IllegalArgumentException.class, () -> service.addManualDevice(H1, dto));
    verify(mapper, never()).insert(any(IotDeviceSnapshot.class));
  }
}
