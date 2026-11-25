-- 设备来源：HUB=中枢自动同步，MANUAL=家庭成员手动添加。
-- 手动设备是本地模拟设备：不依赖中枢、不被同步覆盖、解绑中枢后仍保留。
ALTER TABLE iot_device_snapshot
  ADD COLUMN device_source VARCHAR(16) NOT NULL DEFAULT 'HUB' COMMENT '设备来源: HUB=中枢同步, MANUAL=手动添加';

-- 设备品牌：手动添加时填写；中枢同步设备从 Home Assistant attributes.manufacturer 提取。
ALTER TABLE iot_device_snapshot
  ADD COLUMN brand VARCHAR(64) DEFAULT NULL COMMENT '设备品牌';
