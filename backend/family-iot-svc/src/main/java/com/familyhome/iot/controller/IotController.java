package com.familyhome.iot.controller;

import com.familyhome.common.api.ApiResponse;
import com.familyhome.iot.dto.IotCommandDTO;
import com.familyhome.iot.dto.IotDeviceUpsertDTO;
import com.familyhome.iot.dto.IotHubBindDTO;
import com.familyhome.iot.service.IotCommandService;
import com.familyhome.iot.service.IotDeviceService;
import com.familyhome.iot.service.IotHubService;
import com.familyhome.iot.vo.AreaDevicesVO;
import com.familyhome.iot.vo.DeviceTestResultVO;
import com.familyhome.iot.vo.HubDiscoveryVO;
import com.familyhome.iot.vo.IotDeviceVO;
import com.familyhome.iot.vo.IotHubVO;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * IoT 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/iot")
public class IotController {

  @Autowired
  private IotHubService hubService;

  @Autowired
  private IotDeviceService deviceService;

  @Autowired
  private IotCommandService commandService;

  /**
   * 查询本家庭已绑定的中枢状态（家庭成员，后台静默）
   *
   * <p>云端不在家庭局域网内，局域网发现由家庭侧网关完成并主动注册上报凭据；
   * 本接口既不接收也不返回任何地址 / 令牌。前端只展示「已连接家庭中枢」、
   * 离线提示，以及未绑定时的部署网关 + 配对码引导与「重新查找」。
   */
  @PostMapping("/hub/auto-discover")
  public ApiResponse<HubDiscoveryVO> autoDiscover(HttpServletRequest r) {
    long householdId = SessionPrincipal.from(r).householdId();
    try {
      return ApiResponse.success(hubService.autoDiscover(householdId));
    } catch (Exception e) {
      log.error("查询家庭中枢状态失败", e);
      return ApiResponse.success(HubDiscoveryVO.notProvisioned());
    }
  }

  /**
   * 获取中枢配置
   */
  @GetMapping("/hub")
  public ApiResponse<IotHubVO> getHub(HttpServletRequest r) {
    SessionPrincipal principal = SessionPrincipal.from(r);
    long householdId = principal.householdId();

    IotHubVO hub = hubService.getHub(householdId);
    return ApiResponse.success(hub);
  }

  /**
   * 绑定中枢（家庭成员）
   *
   * <p>产品要求：智能家居只要是家庭成员就可以操作，因此中枢配置不再限制管理员。
   */
  @PutMapping("/hub")
  public ApiResponse<IotHubVO> bindHub(
      HttpServletRequest r,
      @Valid @RequestBody IotHubBindDTO dto) {
    SessionPrincipal principal = SessionPrincipal.from(r);
    long householdId = principal.householdId();

    try {
      IotHubVO hub = hubService.bindHub(householdId, dto);

      // 绑定后立即拉一次设备列表，保证前端拿到中枢就能看到设备；
      // 同步失败不影响绑定结果，只记日志。
      try {
        deviceService.syncDevices(householdId);
      } catch (Exception e) {
        log.warn("绑定后同步设备失败，家庭 {}：{}", householdId, e.getMessage());
      }
      // 注意：生产环境应使用 Spring @Async 或 ThreadPoolTaskExecutor，避免无限制创建线程

      return ApiResponse.success(hub);
    } catch (IllegalArgumentException e) {
      return ApiResponse.failure(400, e.getMessage());
    } catch (Exception e) {
      log.error("绑定中枢失败", e);
      return ApiResponse.failure(500, "绑定失败，请检查地址和令牌");
    }
  }

  /**
   * 解绑中枢（家庭成员）
   */
  @DeleteMapping("/hub")
  public ApiResponse<Void> unbindHub(HttpServletRequest r) {
    SessionPrincipal principal = SessionPrincipal.from(r);
    long householdId = principal.householdId();

    hubService.unbindHub(householdId);
    return ApiResponse.success(null);
  }

  /**
   * 测试中枢连接（家庭成员）
   */
  @PostMapping("/hub/test")
  public ApiResponse<Boolean> testConnection(
      HttpServletRequest r,
      @Valid @RequestBody IotHubBindDTO dto) {
    SessionPrincipal.from(r);

    try {
      boolean ok = hubService.testConnection(dto);
      return ApiResponse.success(ok);
    } catch (Exception e) {
      log.error("测试连接失败", e);
      return ApiResponse.failure(500, "测试失败");
    }
  }

  /**
   * 获取所有设备（按区域分组）
   */
  @GetMapping("/devices")
  public ApiResponse<List<AreaDevicesVO>> getDevices(HttpServletRequest r) {
    SessionPrincipal principal = SessionPrincipal.from(r);
    long householdId = principal.householdId();

    List<AreaDevicesVO> devices = deviceService.getDevicesByArea(householdId);
    return ApiResponse.success(devices);
  }

  /**
   * 获取所有场景
   */
  @GetMapping("/scenes")
  public ApiResponse<List<IotDeviceVO>> getScenes(HttpServletRequest r) {
    SessionPrincipal principal = SessionPrincipal.from(r);
    long householdId = principal.householdId();

    List<IotDeviceVO> scenes = deviceService.getScenes(householdId);
    return ApiResponse.success(scenes);
  }

  /**
   * 手动添加设备（本地模拟，不依赖中枢）。家庭成员均可操作。
   */
  @PostMapping("/devices")
  public ApiResponse<IotDeviceVO> addDevice(
      HttpServletRequest r,
      @Valid @RequestBody IotDeviceUpsertDTO dto) {
    long householdId = SessionPrincipal.from(r).householdId();
    try {
      return ApiResponse.success(deviceService.addManualDevice(householdId, dto));
    } catch (IllegalArgumentException e) {
      return ApiResponse.failure(400, e.getMessage());
    }
  }

  /**
   * 编辑手动设备。只能改 device_source=MANUAL 的设备。
   */
  @PutMapping("/devices/{entityId}")
  public ApiResponse<IotDeviceVO> updateDevice(
      HttpServletRequest r,
      @PathVariable String entityId,
      @Valid @RequestBody IotDeviceUpsertDTO dto) {
    long householdId = SessionPrincipal.from(r).householdId();
    try {
      return ApiResponse.success(deviceService.updateManualDevice(householdId, entityId, dto));
    } catch (IllegalArgumentException e) {
      return ApiResponse.failure(400, e.getMessage());
    }
  }

  /**
   * 删除手动设备。只能删 device_source=MANUAL 的设备。
   */
  @DeleteMapping("/devices/{entityId}")
  public ApiResponse<Void> deleteDevice(HttpServletRequest r, @PathVariable String entityId) {
    long householdId = SessionPrincipal.from(r).householdId();
    try {
      deviceService.deleteManualDevice(householdId, entityId);
      return ApiResponse.success(null);
    } catch (IllegalArgumentException e) {
      return ApiResponse.failure(400, e.getMessage());
    }
  }

  /**
   * 探测单台设备是否真实响应
   *
   * <p>真实中枢设备会读取 Home Assistant 上该实体的实时状态；
   * 手动/演示设备返回"本地模拟"说明。只能探测本家庭设备。
   */
  @PostMapping("/devices/{entityId}/test")
  public ApiResponse<DeviceTestResultVO> testDevice(
      HttpServletRequest r,
      @PathVariable String entityId) {
    long householdId = SessionPrincipal.from(r).householdId();
    try {
      return ApiResponse.success(deviceService.testDevice(householdId, entityId));
    } catch (IllegalArgumentException e) {
      return ApiResponse.failure(400, e.getMessage());
    } catch (IllegalStateException e) {
      return ApiResponse.failure(400, e.getMessage());
    } catch (Exception e) {
      log.error("探测设备响应失败", e);
      return ApiResponse.failure(500, "探测失败");
    }
  }

  /**
   * 执行设备命令
   */
  @PostMapping("/devices/{entityId}/command")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ApiResponse<Void> executeCommand(
      HttpServletRequest r,
      @PathVariable String entityId,
      @Valid @RequestBody IotCommandDTO dto) {
    SessionPrincipal principal = SessionPrincipal.from(r);
    long householdId = principal.householdId();
    long userId = principal.userId();

    try {
      commandService.executeCommand(householdId, userId, entityId, dto);
      return ApiResponse.success(null);
    } catch (IllegalArgumentException e) {
      return ApiResponse.failure(400, e.getMessage());
    } catch (Exception e) {
      log.error("执行命令失败", e);
      return ApiResponse.failure(500, "命令执行失败");
    }
  }

  /**
   * 触发场景
   */
  @PostMapping("/scenes/{entityId}/activate")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ApiResponse<Void> activateScene(
      HttpServletRequest r,
      @PathVariable String entityId) {
    SessionPrincipal principal = SessionPrincipal.from(r);
    long householdId = principal.householdId();
    long userId = principal.userId();

    try {
      commandService.activateScene(householdId, userId, entityId);
      return ApiResponse.success(null);
    } catch (IllegalArgumentException e) {
      return ApiResponse.failure(400, e.getMessage());
    } catch (Exception e) {
      log.error("触发场景失败", e);
      return ApiResponse.failure(500, "场景触发失败");
    }
  }
}
