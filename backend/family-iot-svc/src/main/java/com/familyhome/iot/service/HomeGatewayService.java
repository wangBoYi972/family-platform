package com.familyhome.iot.service;

import com.familyhome.iot.dto.GatewayDeviceReportDTO;
import com.familyhome.iot.dto.GatewayRegisterDTO;
import com.familyhome.iot.model.IotGateway;
import com.familyhome.iot.vo.GatewayRegisterVO;
import com.familyhome.iot.vo.PairingCodeVO;

public interface HomeGatewayService {
  PairingCodeVO createPairingCode(Long householdId, Long userId);

  GatewayRegisterVO register(GatewayRegisterDTO dto);

  IotGateway authenticate(String gatewayToken);

  void touch(IotGateway gateway);

  void replaceDevices(Long householdId, GatewayDeviceReportDTO report);

  void updateDeviceState(
      Long householdId, String entityId, String state, java.util.Map<String, Object> attributes);
}
