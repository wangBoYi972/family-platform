package com.familyhome.iot.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地联调：不连真云，指令视为成功（由上层改快照）。
 * 正式落地请改 family.iot.cloud.provider=tuya 并配置密钥。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "family.iot.cloud.provider", havingValue = "sim", matchIfMissing = true)
public class SimCloudClient implements IotCloudClient {

  @Override
  public boolean isReady() {
    return true;
  }

  @Override
  public String providerId() {
    return "sim";
  }

  @Override
  public List<CloudDeviceInfo> listDevices() {
    // 联调样例：正式环境由涂鸦账号设备列表替换；便于无密钥时走通「导入 → 控制」全流程
    List<CloudDeviceInfo> out = new ArrayList<>();
    out.add(sample("sim-ac-living", "客厅空调", "格力", "kt", "climate", true));
    out.add(sample("sim-tv-living", "客厅电视", "小米", "dj", "media_player", true));
    out.add(sample("sim-ir-bedroom", "卧室红外伴侣", "涂鸦", "wf_ir", "switch", true));
    return out;
  }

  private static CloudDeviceInfo sample(
      String id, String name, String brand, String category, String entityType, boolean online) {
    CloudDeviceInfo info = new CloudDeviceInfo();
    info.setCloudDeviceId(id);
    info.setName(name);
    info.setBrand(brand);
    info.setCategory(category);
    info.setEntityType(entityType);
    info.setOnline(online);
    return info;
  }

  @Override
  public boolean sendCommand(String cloudDeviceId, String entityType, String commandType, JsonNode parameters) {
    log.info("[sim-cloud] 设备 {} {} {}（联调模式，未发真机）", cloudDeviceId, entityType, commandType);
    return cloudDeviceId != null && !cloudDeviceId.isBlank();
  }
}
