package com.familyhome.iot;

import com.familyhome.iot.dto.IotCommandDTO;
import com.familyhome.iot.dto.IotHubBindDTO;
import com.familyhome.iot.service.IotCommandService;
import com.familyhome.iot.service.IotDeviceService;
import com.familyhome.iot.service.IotHubService;
import com.familyhome.iot.vo.AreaDevicesVO;
import com.familyhome.iot.vo.IotDeviceVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 演示中枢下空调 / 电视 / 音响控制闭环。
 */
@SpringBootTest
@ActiveProfiles("test")
public class DemoDeviceControlTest {

  private static final long HOUSEHOLD = 91001L;
  private static final long USER = 1L;

  @Autowired(required = false)
  private IotHubService hubService;

  @Autowired(required = false)
  private IotDeviceService deviceService;

  @Autowired(required = false)
  private IotCommandService commandService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void demoSeedsClimateAndMediaAndAcceptsControls() throws Exception {
    if (hubService == null || deviceService == null || commandService == null) {
      return;
    }

    IotHubBindDTO bind = new IotHubBindDTO();
    bind.setBaseUrl("demo://local");
    bind.setAccessToken("demo");
    hubService.bindHub(HOUSEHOLD, bind);
    deviceService.syncDevices(HOUSEHOLD);

    List<AreaDevicesVO> areas = deviceService.getDevicesByArea(HOUSEHOLD);
    assertFalse(areas.isEmpty());
    assertTrue(find(areas, "climate.living_room_ac") != null);
    assertTrue(find(areas, "media_player.living_room_tv") != null);
    assertTrue(find(areas, "media_player.living_room_speaker") != null);

    // 空调关机
    commandService.executeCommand(HOUSEHOLD, USER, "climate.living_room_ac", cmd("turn_off"));
    assertEquals("off", find(areasAfter(), "climate.living_room_ac").getState());

    // 越界温度必须 400
    IotCommandDTO badTemp = cmd("set_temperature");
    ObjectNode badParams = objectMapper.createObjectNode();
    badParams.put("temperature", 99);
    badTemp.setParameters(badParams);
    assertThrows(IllegalArgumentException.class,
        () -> commandService.executeCommand(HOUSEHOLD, USER, "climate.living_room_ac", badTemp));

    // 合法调温并开机
    IotCommandDTO temp = cmd("set_temperature");
    ObjectNode params = objectMapper.createObjectNode();
    params.put("temperature", 24);
    temp.setParameters(params);
    commandService.executeCommand(HOUSEHOLD, USER, "climate.living_room_ac", temp);
    IotDeviceVO ac = find(areasAfter(), "climate.living_room_ac");
    assertNotEquals("off", ac.getState());
    assertEquals(24, ac.getAttributes().path("temperature").asInt());

    // 电视开机 + 调音量
    commandService.executeCommand(HOUSEHOLD, USER, "media_player.living_room_tv", cmd("turn_on"));
    IotCommandDTO vol = cmd("volume_set");
    ObjectNode volParams = objectMapper.createObjectNode();
    volParams.put("volume_level", 0.7);
    vol.setParameters(volParams);
    commandService.executeCommand(HOUSEHOLD, USER, "media_player.living_room_tv", vol);
    IotDeviceVO tv = find(areasAfter(), "media_player.living_room_tv");
    assertEquals("playing", tv.getState());
    assertEquals(0.7, tv.getAttributes().path("volume_level").asDouble(), 0.001);

    // 非白名单命令拒绝
    assertThrows(IllegalArgumentException.class,
        () -> commandService.executeCommand(HOUSEHOLD, USER, "climate.living_room_ac", cmd("hack")));
  }

  private List<AreaDevicesVO> areasAfter() {
    return deviceService.getDevicesByArea(HOUSEHOLD);
  }

  private static IotDeviceVO find(List<AreaDevicesVO> areas, String entityId) {
    for (AreaDevicesVO area : areas) {
      for (IotDeviceVO d : area.getDevices()) {
        if (entityId.equals(d.getEntityId())) return d;
      }
    }
    return null;
  }

  private IotCommandDTO cmd(String type) {
    IotCommandDTO dto = new IotCommandDTO();
    dto.setCommandType(type);
    return dto;
  }
}
