package com.familyhome.iot.controller;

import com.familyhome.common.api.ApiResponse;
import com.familyhome.iot.dto.GatewayDeviceReportDTO;
import com.familyhome.iot.dto.GatewayRegisterDTO;
import com.familyhome.iot.gateway.GatewaySessionRegistry;
import com.familyhome.iot.model.IotGateway;
import com.familyhome.iot.service.HomeGatewayService;
import com.familyhome.iot.service.IotHubService;
import com.familyhome.iot.vo.GatewayRegisterVO;
import com.familyhome.iot.vo.PairingCodeVO;
import com.familyhome.security.SessionPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/iot")
public class GatewayController {

  @Autowired private HomeGatewayService homeGatewayService;
  @Autowired private IotHubService hubService;
  @Autowired private GatewaySessionRegistry sessionRegistry;

  /** 家庭成员创建配对码，给家里的网关进程填写。 */
  @PostMapping("/hub/pairing-code")
  public ApiResponse<PairingCodeVO> createPairingCode(HttpServletRequest request) {
    SessionPrincipal principal = SessionPrincipal.from(request);
    PairingCodeVO vo =
        homeGatewayService.createPairingCode(principal.householdId(), principal.userId());
    return ApiResponse.success(vo);
  }

  /** 网关用配对码注册；匿名接口，鉴权靠短时配对码。 */
  @PostMapping("/gateway/register")
  public ApiResponse<GatewayRegisterVO> register(@Valid @RequestBody GatewayRegisterDTO dto) {
    try {
      return ApiResponse.success(homeGatewayService.register(dto));
    } catch (IllegalArgumentException e) {
      return ApiResponse.failure(400, e.getMessage());
    } catch (Exception e) {
      log.error("网关注册失败", e);
      return ApiResponse.failure(500, "网关注册失败");
    }
  }

  /** 网关全量上报本地可控设备（红外/模拟执行器）。 */
  @PostMapping("/gateway/devices")
  public ApiResponse<Void> reportDevices(
      @RequestHeader(value = "X-Gateway-Token", required = false) String headerToken,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody GatewayDeviceReportDTO report) {
    try {
      IotGateway gateway = homeGatewayService.authenticate(resolveToken(headerToken, authorization));
      homeGatewayService.touch(gateway);
      homeGatewayService.replaceDevices(gateway.getHouseholdId(), report);
      return ApiResponse.success(null);
    } catch (IllegalArgumentException e) {
      return ApiResponse.failure(401, e.getMessage());
    } catch (Exception e) {
      log.error("网关设备上报失败", e);
      return ApiResponse.failure(500, "设备上报失败");
    }
  }

  /** 网关回报单设备状态（命令执行后）。 */
  @PostMapping("/gateway/state")
  public ApiResponse<Void> reportState(
      @RequestHeader(value = "X-Gateway-Token", required = false) String headerToken,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody JsonNode body) {
    try {
      IotGateway gateway = homeGatewayService.authenticate(resolveToken(headerToken, authorization));
      homeGatewayService.touch(gateway);
      String entityId = body.path("entityId").asText(null);
      String state = body.path("state").asText(null);
      if (entityId == null || state == null) {
        return ApiResponse.failure(400, "缺少 entityId/state");
      }
      Map<String, Object> attributes = new HashMap<>();
      JsonNode attrs = body.path("attributes");
      if (attrs.isObject()) {
        attrs.fields().forEachRemaining(e -> attributes.put(e.getKey(), unwrap(e.getValue())));
      }
      homeGatewayService.updateDeviceState(
          gateway.getHouseholdId(), entityId, state, attributes.isEmpty() ? null : attributes);
      return ApiResponse.success(null);
    } catch (IllegalArgumentException e) {
      return ApiResponse.failure(401, e.getMessage());
    } catch (Exception e) {
      log.error("网关状态上报失败", e);
      return ApiResponse.failure(500, "状态上报失败");
    }
  }

  /** 成员查看网关是否在线。 */
  @GetMapping("/gateway/status")
  public ApiResponse<Map<String, Object>> status(HttpServletRequest request) {
    SessionPrincipal principal = SessionPrincipal.from(request);
    var hub = hubService.getHub(principal.householdId());
    Map<String, Object> data = new HashMap<>();
    data.put("configured", Boolean.TRUE.equals(hub.getConfigured()));
    data.put("gateway", Boolean.TRUE.equals(hub.getGateway()));
    data.put("online", sessionRegistry.isOnline(principal.householdId()));
    return ApiResponse.success(data);
  }

  private static String resolveToken(String headerToken, String authorization) {
    if (headerToken != null && !headerToken.isBlank()) return headerToken.trim();
    if (authorization != null && authorization.startsWith("Bearer ")) {
      return authorization.substring(7).trim();
    }
    if (authorization != null && authorization.startsWith("Gateway ")) {
      return authorization.substring(8).trim();
    }
    return null;
  }

  private static Object unwrap(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (node.isNumber()) return node.numberValue();
    if (node.isBoolean()) return node.booleanValue();
    if (node.isTextual()) return node.asText();
    return node.toString();
  }
}
