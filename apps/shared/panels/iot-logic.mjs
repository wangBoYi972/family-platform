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
      isClimate: device.entityType === 'climate',
      isMedia: device.entityType === 'media_player',
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
 * 空调的 state 是模式（cool/heat/off...），不是 on/off
 */
export function isDeviceOn(device) {
  if (device.entityType === 'climate') return device.state !== 'off' && !!device.state;
  return device.state === 'on';
}

// ------------------------------------------------------------------ 空调

/** 空调目标温度（无则取当前温度或默认 26） */
export function getTargetTemp(device) {
  const t = device.attributes?.temperature;
  if (typeof t === 'number') return t;
  const cur = device.attributes?.current_temperature;
  return typeof cur === 'number' ? Math.round(cur) : 26;
}

/** 空调当前室温 */
export function getCurrentTemp(device) {
  const cur = device.attributes?.current_temperature;
  return typeof cur === 'number' ? cur : null;
}

/** 运行模式选项（后端 attributes 会给 hvac_modes，给不出就用默认） */
export function getHvacModes(device) {
  const modes = device.attributes?.hvac_modes;
  return Array.isArray(modes) && modes.length ? modes : ['cool', 'heat', 'dry', 'fan_only', 'auto', 'off'];
}

/** 风速选项 */
export function getFanModes(device) {
  const modes = device.attributes?.fan_modes;
  return Array.isArray(modes) && modes.length ? modes : ['auto', 'low', 'mid', 'high'];
}

/** 当前运行模式 */
export function getHvacMode(device) {
  return device.attributes?.hvac_mode || (device.state === 'off' ? 'off' : device.state) || 'cool';
}

/** 当前风速 */
export function getFanMode(device) {
  return device.attributes?.fan_mode || 'auto';
}

// ------------------------------------------------------------------ 电视 / 音响

/** 音量百分比 0-100 */
export function getVolumePct(device) {
  const v = device.attributes?.volume_level;
  if (typeof v !== 'number') return 0;
  return Math.round(v * 100);
}

/** 是否静音 */
export function isMuted(device) {
  return !!device.attributes?.is_volume_muted;
}

/** 频道/音源列表 */
export function getSources(device) {
  const list = device.attributes?.source_list;
  return Array.isArray(list) ? list : [];
}

/** 当前频道/音源 */
export function getCurrentSource(device) {
  return device.attributes?.source || '';
}

/** 媒体标题 */
export function getMediaTitle(device) {
  return device.attributes?.media_title || '';
}

/** 是否是电视（有频道列表且名字带电视/TV，展示频道控制） */
export function isTv(device) {
  if (device.entityType !== 'media_player') return false;
  const name = (device.deviceName || device.entityId || '').toLowerCase();
  return name.includes('电视') || name.includes('tv') || name.includes('投影');
}

/** 模式/风速的中文显示 */
export function modeLabel(mode) {
  const labels = {
    cool: '制冷', heat: '制热', dry: '除湿', fan_only: '送风',
    auto: '自动', off: '关闭', low: '低速', mid: '中速', high: '高速'
  };
  return labels[mode] || mode;
}
