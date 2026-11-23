CREATE TABLE IF NOT EXISTS iot_hub (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  household_id BIGINT NOT NULL UNIQUE,
  base_url VARCHAR(255) NOT NULL,
  access_token VARCHAR(1024) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_household (household_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS iot_device_snapshot (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  household_id BIGINT NOT NULL,
  entity_id VARCHAR(255) NOT NULL,
  entity_type VARCHAR(50) NOT NULL,
  device_name VARCHAR(255),
  area_name VARCHAR(255),
  state VARCHAR(255),
  attributes JSON,
  is_online TINYINT(1) DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_household_entity (household_id, entity_id),
  KEY idx_household_area (household_id, area_name),
  KEY idx_household_type (household_id, entity_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS iot_command_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  household_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  entity_id VARCHAR(255) NOT NULL,
  command_type VARCHAR(50) NOT NULL,
  command_domain VARCHAR(50) NOT NULL,
  command_service VARCHAR(50) NOT NULL,
  request_data JSON,
  success TINYINT(1),
  error_message VARCHAR(512),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  KEY idx_household (household_id),
  KEY idx_user (user_id),
  KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
