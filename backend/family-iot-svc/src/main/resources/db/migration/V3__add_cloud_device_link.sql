-- 云端直连设备（涂鸦等）：不依赖 Home Assistant 中枢。
ALTER TABLE iot_device_snapshot
  ADD COLUMN cloud_device_id VARCHAR(64) DEFAULT NULL COMMENT '云平台设备 ID（如涂鸦 device_id）';

ALTER TABLE iot_device_snapshot
  ADD COLUMN cloud_provider VARCHAR(32) DEFAULT NULL COMMENT '云平台：tuya / sim';
