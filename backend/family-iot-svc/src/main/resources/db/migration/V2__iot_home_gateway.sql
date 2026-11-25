-- 家侧网关配对：短码把网关挂到某个家庭；网关凭据用于后续出站连接与上报。
CREATE TABLE IF NOT EXISTS iot_pairing_code (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  household_id BIGINT NOT NULL,
  code_hash CHAR(64) NOT NULL,
  code_tail VARCHAR(8) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  used_at TIMESTAMP NULL,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_code_hash (code_hash),
  KEY idx_pair_household (household_id),
  KEY idx_pair_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS iot_gateway (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  household_id BIGINT NOT NULL UNIQUE,
  gateway_id VARCHAR(64) NOT NULL UNIQUE,
  token_hash CHAR(64) NOT NULL,
  display_name VARCHAR(128) NOT NULL DEFAULT '家庭网关',
  last_seen_at TIMESTAMP NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_gateway_seen (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
