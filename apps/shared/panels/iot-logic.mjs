/**
 * IoT 设备和命令业务逻辑
 *
 * 分组、离线标记、开关判定、亮度等纯逻辑
 */

const SWITCHABLE_TYPES = new Set(["light", "switch", "climate", "media_player"]);

/**
 * 按区域分组设备
 */
export function groupDevicesByArea(devices) {
  const grouped = {};

  for (const [area, deviceList] of Object.entries(devices)) {
    grouped[area] = (deviceList || []).map(device => ({
      ...device,
      isOnline: device.isOnline !== false,
      isSensor: device.entityType === "sensor",
      isLight: device.entityType === "light",
      isSwitch: device.entityType === "switch",
      isClimate: device.entityType === "climate",
      isMedia: device.entityType === "media_player",
      isSwitchable: isSwitchable(device),
    }));
  }

  return grouped;
}

/**
 * 获取设备的显示名称
 */
export function getDeviceName(device) {
  return device.deviceName || device.entityId;
}

/**
 * 检查设备是否离线
 */
export function isDeviceOffline(device) {
  return device.isOnline === false;
}

/**
 * 灯 / 插座 / 空调 / 电视音响可开关；传感器只读。
 */
export function isSwitchable(device) {
  return SWITCHABLE_TYPES.has(device?.entityType);
}

/**
 * 获取灯的亮度
 */
export function getLightBrightness(device) {
  if (!device.attributes || !device.attributes.brightness) {
    return 0;
  }
  // Home Assistant 亮度范围 0-255
  return Math.round((device.attributes.brightness / 255) * 100);
}

/**
 * 检查灯是否支持亮度调节
 */
export function supportsColorControl(device) {
  if (device.entityType !== "light") return false;
  const supportedFeatures = device.attributes?.supported_features || 0;
  // 位标志：1=亮度
  return (supportedFeatures & 1) !== 0;
}

/**
 * 获取传感器的显示值
 */
export function getSensorValue(device) {
  const state = device.state;
  const unit = device.attributes?.unit_of_measurement || "";
  return unit ? `${state} ${unit}` : state;
}

/**
 * 检查设备状态是否为开启（空调/播放器用非 off 语义）
 */
export function isDeviceOn(device) {
  const state = device?.state;
  if (!state) return false;
  if (device.entityType === "climate") {
    return state !== "off";
  }
  if (device.entityType === "media_player") {
    return state !== "off" && state !== "idle" && state !== "standby";
  }
  return state === "on";
}

export function getClimateTargetTemp(device) {
  const raw = device?.attributes?.temperature;
  const n = Number(raw);
  return Number.isFinite(n) ? n : 26;
}

export function getMediaVolumePct(device) {
  const raw = device?.attributes?.volume_level;
  const n = Number(raw);
  if (!Number.isFinite(n)) return 40;
  return Math.round(Math.min(1, Math.max(0, n)) * 100);
}

export function deviceTypeLabel(device) {
  const map = {
    light: "灯",
    switch: "开关",
    sensor: "传感器",
    climate: "空调",
    media_player: "影音",
    scene: "场景",
  };
  return map[device?.entityType] || "设备";
}
