/**
 * IoT 设备和命令业务逻辑
 *
 * 分组、离线标记、命令体等纯逻辑
 */

/**
 * 按区域分组设备
 */
export function groupDevicesByArea(devices) {
  const grouped = {};

  for (const [area, deviceList] of Object.entries(devices)) {
    grouped[area] = (deviceList || []).map(device => ({
      ...device,
      isOnline: device.isOnline !== false,
      isSensor: device.entityType === 'sensor',
      isLight: device.entityType === 'light',
      isSwitch: device.entityType === 'switch',
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
  if (device.entityType !== 'light') return false;
  const supportedFeatures = device.attributes?.supported_features || 0;
  // 位标志：1=亮度
  return (supportedFeatures & 1) !== 0;
}

/**
 * 获取传感器的显示值
 */
export function getSensorValue(device) {
  const state = device.state;
  const unit = device.attributes?.unit_of_measurement || '';
  return unit ? `${state} ${unit}` : state;
}

/**
 * 检查设备状态是否为开启
 */
export function isDeviceOn(device) {
  return device.state === 'on';
}
