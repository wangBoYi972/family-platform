package com.familyhome.iot.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 家庭设备云通道（涂鸦等）。手机 App → 本平台 → 云厂商 → 家里 Wi‑Fi 设备。
 */
public interface IotCloudClient {

  /** 部署侧是否已配好可用密钥。 */
  boolean isReady();

  String providerId();

  /** 拉取账号下可绑定的设备（失败返回空列表，不抛给前端堆栈）。 */
  List<CloudDeviceInfo> listDevices() throws Exception;

  /**
   * 下发控制指令。
   *
   * @param cloudDeviceId 云平台设备 ID
   * @param entityType    climate / media_player / light / switch
   * @param commandType   turn_on / set_temperature 等
   * @param parameters    命令参数
   */
  boolean sendCommand(String cloudDeviceId, String entityType, String commandType, JsonNode parameters)
      throws Exception;
}
