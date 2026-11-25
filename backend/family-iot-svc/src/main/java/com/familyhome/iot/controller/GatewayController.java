package com.familyhome.iot.controller;

import com.familyhome.common.api.ApiResponse;
import com.familyhome.iot.dto.GatewayDeviceReportDTO;
import com.familyhome.iot.dto.GatewayRegisterDTO;
import com.familyhome.iot.dto.GatewayStateReportDTO;
import com.familyhome.iot.service.HomeGatewayService;
import com.familyhome.iot.vo.GatewayRegisterVO;
import com.familyhome.iot.vo.GatewayStatusVO;
import com.familyhome.iot.vo.PairingCodeVO;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
      homeGatewayService.reportDevices(resolveToken(headerToken, authorization), report);
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
      @Valid @RequestBody GatewayStateReportDTO report) {
    try {
      homeGatewayService.reportState(resolveToken(headerToken, authorization), report);
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
  public ApiResponse<GatewayStatusVO> status(HttpServletRequest request) {
    SessionPrincipal principal = SessionPrincipal.from(request);
    return ApiResponse.success(homeGatewayService.status(principal.householdId()));
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
}
