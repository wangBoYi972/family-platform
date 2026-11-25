package com.familyhome.iot.service;

import com.familyhome.iot.dto.GatewayDeviceReportDTO;
import com.familyhome.iot.dto.GatewayRegisterDTO;
import com.familyhome.iot.dto.GatewayStateReportDTO;
import com.familyhome.iot.model.IotGateway;
import com.familyhome.iot.vo.GatewayRegisterVO;
import com.familyhome.iot.vo.GatewayStatusVO;
import com.familyhome.iot.vo.PairingCodeVO;

public interface HomeGatewayService {
  PairingCodeVO createPairingCode(Long householdId, Long userId);

  GatewayRegisterVO register(GatewayRegisterDTO dto);

  IotGateway authenticate(String gatewayToken);

  void touch(IotGateway gateway);

  void replaceDevices(Long householdId, GatewayDeviceReportDTO report);

  void updateDeviceState(
      Long householdId, String entityId, String state, java.util.Map<String, Object> attributes);

  /** 网关凭令牌上报设备（内部鉴权）。 */
  void reportDevices(String gatewayToken, GatewayDeviceReportDTO report);

  /** 网关凭令牌上报单设备状态（内部鉴权）。 */
  void reportState(String gatewayToken, GatewayStateReportDTO report);

  GatewayStatusVO status(Long householdId);

  boolean isOnline(Long householdId);
}
