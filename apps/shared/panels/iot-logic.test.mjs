/**
 * IoT 逻辑测试
 */

import {
  groupDevicesByArea,
  getDeviceName,
  isDeviceOffline,
  getLightBrightness,
  supportsColorControl,
  getSensorValue,
  isDeviceOn,
} from './iot-logic.mjs';

// 测试数据
const mockDevices = {
  '客厅': [
    {
      entityId: 'light.living_room',
      deviceName: '客厅灯',
      entityType: 'light',
      state: 'on',
      attributes: {
        brightness: 200,
        supported_features: 1,
      },
      isOnline: true,
    },
  ],
  '卧室': [
    {
      entityId: 'light.bedroom',
      deviceName: '卧室灯',
      entityType: 'light',
      state: 'off',
      attributes: { brightness: 0 },
      isOnline: true,
    },
    {
      entityId: 'sensor.bedroom_temp',
      deviceName: '卧室温度',
      entityType: 'sensor',
      state: '22.5',
      attributes: { unit_of_measurement: '°C' },
      isOnline: true,
    },
  ],
};

// 测试用例
export function runTests() {
  let passed = 0;
  let failed = 0;

  // 测试 1：分组
  try {
    const grouped = groupDevicesByArea(mockDevices);
    if (grouped['客厅'].length === 1 && grouped['卧室'].length === 2) {
      console.log('✓ 分组测试通过');
      passed++;
    } else {
      console.error('✗ 分组测试失败');
      failed++;
    }
  } catch (e) {
    console.error('✗ 分组测试异常:', e.message);
    failed++;
  }

  // 测试 2：离线标记
  const device = mockDevices['客厅'][0];
  try {
    if (!isDeviceOffline(device)) {
      console.log('✓ 离线标记测试通过');
      passed++;
    } else {
      console.error('✗ 离线标记测试失败');
      failed++;
    }
  } catch (e) {
    console.error('✗ 离线标记测试异常:', e.message);
    failed++;
  }

  // 测试 3：亮度获取
  try {
    const brightness = getLightBrightness(device);
    if (brightness === 78) { // Math.round(200/255*100) = 78
      console.log('✓ 亮度获取测试通过');
      passed++;
    } else {
      console.error(`✗ 亮度获取测试失败: 期望 78，得到 ${brightness}`);
      failed++;
    }
  } catch (e) {
    console.error('✗ 亮度获取测试异常:', e.message);
    failed++;
  }

  // 测试 4：设备名称
  try {
    const name = getDeviceName(device);
    if (name === '客厅灯') {
      console.log('✓ 设备名称测试通过');
      passed++;
    } else {
      console.error('✗ 设备名称测试失败');
      failed++;
    }
  } catch (e) {
    console.error('✗ 设备名称测试异常:', e.message);
    failed++;
  }

  // 测试 5：设备状态
  try {
    if (isDeviceOn(device)) {
      console.log('✓ 设备状态测试通过');
      passed++;
    } else {
      console.error('✗ 设备状态测试失败');
      failed++;
    }
  } catch (e) {
    console.error('✗ 设备状态测试异常:', e.message);
    failed++;
  }

  // 测试 6：传感器值
  const sensorDevice = mockDevices['卧室'][1];
  try {
    const value = getSensorValue(sensorDevice);
    if (value === '22.5 °C') {
      console.log('✓ 传感器值测试通过');
      passed++;
    } else {
      console.error(`✗ 传感器值测试失败: 得到 ${value}`);
      failed++;
    }
  } catch (e) {
    console.error('✗ 传感器值测试异常:', e.message);
    failed++;
  }

  console.log(`\n总计: ${passed} 通过, ${failed} 失败`);
  return failed === 0;
}

// 运行测试
if (import.meta.url === `file://${process.argv[1]}`) {
  process.exit(runTests() ? 0 : 1);
}
