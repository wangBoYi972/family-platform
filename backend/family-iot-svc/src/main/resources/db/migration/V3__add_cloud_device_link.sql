-- 云端直连设备（涂鸦等）：不依赖 Home Assistant 中枢。
ALTER TABLE iot_device_snapshot
  ADD COLUMN cloud_device_id VARCHAR(64) DEFAULT NULL COMMENT '云平台设备 ID（如涂鸦 device_id）';

ALTER TABLE iot_device_snapshot
  ADD COLUMN cloud_provider VARCHAR(32) DEFAULT NULL COMMENT '云平台：tuya / sim';

-- 同一家庭同一云设备只能添加一次（MySQL 允许多个 NULL，不影响 MANUAL）
CREATE UNIQUE INDEX uk_household_cloud_device
  ON iot_device_snapshot (household_id, cloud_device_id);
