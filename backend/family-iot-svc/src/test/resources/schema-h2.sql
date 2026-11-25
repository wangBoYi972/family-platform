CREATE TABLE IF NOT EXISTS iot_hub (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  household_id BIGINT NOT NULL UNIQUE,
  base_url VARCHAR(255) NOT NULL,
  access_token VARCHAR(1024) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS iot_device_snapshot (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  household_id BIGINT NOT NULL,
  entity_id VARCHAR(255) NOT NULL,
  entity_type VARCHAR(50) NOT NULL,
  device_name VARCHAR(255),
  area_name VARCHAR(255),
  state VARCHAR(255),
  attributes VARCHAR(4000),
  is_online BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_household_entity UNIQUE (household_id, entity_id)
);

CREATE TABLE IF NOT EXISTS iot_command_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  household_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  entity_id VARCHAR(255) NOT NULL,
  command_type VARCHAR(50) NOT NULL,
  command_domain VARCHAR(50) NOT NULL,
  command_service VARCHAR(50) NOT NULL,
  request_data VARCHAR(4000),
  success BOOLEAN,
  error_message VARCHAR(512),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
