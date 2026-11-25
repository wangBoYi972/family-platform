package com.familyhome.iot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.familyhome.iot.dto.GatewayDeviceReportDTO;
import com.familyhome.iot.dto.GatewayRegisterDTO;
import com.familyhome.iot.dto.IotCommandDTO;
import com.familyhome.iot.gateway.GatewaySessionRegistry;
import com.familyhome.iot.service.HomeGatewayService;
import com.familyhome.iot.service.IotCommandService;
import com.familyhome.iot.service.IotDeviceService;
import com.familyhome.iot.service.IotHubService;
import com.familyhome.iot.vo.AreaDevicesVO;
import com.familyhome.iot.vo.GatewayRegisterVO;
import com.familyhome.iot.vo.IotDeviceVO;
import com.familyhome.iot.vo.IotHubVO;
import com.familyhome.iot.vo.PairingCodeVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 家侧网关：配对 → 上报设备 → 离线命令拒 → 在线 ACK 后状态更新。
 */
@SpringBootTest
@ActiveProfiles("test")
public class HomeGatewayPairingTest {

  private static final long HOUSEHOLD = 92001L;
  private static final long USER = 2L;

  @Autowired(required = false)
  private HomeGatewayService homeGatewayService;

  @Autowired(required = false)
  private IotHubService hubService;

  @Autowired(required = false)
  private IotDeviceService deviceService;

  @Autowired(required = false)
  private IotCommandService commandService;

  @Autowired(required = false)
  private GatewaySessionRegistry sessionRegistry;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void pairingRegistersGatewayAndCommandsRequireOnlineAck() throws Exception {
    if (homeGatewayService == null || hubService == null || commandService == null) {
      return;
    }

    hubService.unbindHub(HOUSEHOLD);

    PairingCodeVO pairing = homeGatewayService.createPairingCode(HOUSEHOLD, USER);
    assertNotNull(pairing.getCode());
    assertEquals(8, pairing.getCode().length());

    GatewayRegisterDTO reg = new GatewayRegisterDTO();
    reg.setPairingCode(pairing.getCode());
    reg.setDisplayName("测试网关");
    GatewayRegisterVO registered = homeGatewayService.register(reg);
    assertNotNull(registered.getGatewayId());
    assertNotNull(registered.getGatewayToken());
    assertEquals(HOUSEHOLD, registered.getHouseholdId());

    IotHubVO hub = hubService.getHub(HOUSEHOLD);
    assertTrue(Boolean.TRUE.equals(hub.getConfigured()));
    assertTrue(Boolean.TRUE.equals(hub.getGateway()));
    assertFalse(Boolean.TRUE.equals(hub.getDemo()));

    GatewayRegisterDTO reuse = new GatewayRegisterDTO();
    reuse.setPairingCode(pairing.getCode());
    assertThrows(IllegalArgumentException.class, () -> homeGatewayService.register(reuse));

    GatewayDeviceReportDTO report = new GatewayDeviceReportDTO();
    GatewayDeviceReportDTO.DeviceItem ac = new GatewayDeviceReportDTO.DeviceItem();
    ac.setEntityId("climate.gw_ac");
    ac.setEntityType("climate");
    ac.setDeviceName("网关空调");
    ac.setAreaName("客厅");
    ac.setState("off");
    ac.setAttributes(Map.of("temperature", 26, "hvac_mode", "cool"));
    ac.setOnline(true);
    report.setDevices(List.of(ac));
    homeGatewayService.replaceDevices(HOUSEHOLD, report);

    assertNotNull(find(deviceService.getDevicesByArea(HOUSEHOLD), "climate.gw_ac"));

    IotCommandDTO on = new IotCommandDTO();
    on.setCommandType("turn_on");
    assertThrows(
        IllegalStateException.class,
        () -> commandService.executeCommand(HOUSEHOLD, USER, "climate.gw_ac", on));

    if (sessionRegistry == null) return;

    WebSocketSession fake = mock(WebSocketSession.class);
    when(fake.getId()).thenReturn(UUID.randomUUID().toString());
    when(fake.isOpen()).thenReturn(true);
    doAnswer(
            invocation -> {
              TextMessage message = invocation.getArgument(0);
              ObjectNode msg = (ObjectNode) objectMapper.readTree(message.getPayload());
              if ("command".equals(msg.path("type").asText())) {
                ObjectNode ack = objectMapper.createObjectNode();
                ack.put("type", "command.ack");
                ack.put("requestId", msg.path("requestId").asText());
                ack.put("ok", true);
                ack.put("state", "cool");
                ObjectNode attrs = ack.putObject("attributes");
                attrs.put("temperature", 26);
                attrs.put("hvac_mode", "cool");
                sessionRegistry.complete(msg.path("requestId").asText(), ack);
              }
              return null;
            })
        .when(fake)
        .sendMessage(any());

    sessionRegistry.bind(HOUSEHOLD, fake);
    assertTrue(sessionRegistry.isOnline(HOUSEHOLD));

    commandService.executeCommand(HOUSEHOLD, USER, "climate.gw_ac", on);
    IotDeviceVO after = find(deviceService.getDevicesByArea(HOUSEHOLD), "climate.gw_ac");
    assertEquals("cool", after.getState());

    sessionRegistry.unbind(HOUSEHOLD, fake);
  }

  private static IotDeviceVO find(List<AreaDevicesVO> areas, String entityId) {
    for (AreaDevicesVO area : areas) {
      for (IotDeviceVO d : area.getDevices()) {
        if (entityId.equals(d.getEntityId())) return d;
      }
    }
    return null;
  }
}
