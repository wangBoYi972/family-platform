-- 提问可选带 client_id，便于排查；幂等靠下面的专用表，避免 NULL 唯一索引在 H2/MySQL 行为不一致
ALTER TABLE ai_message ADD COLUMN client_id VARCHAR(36) NULL;

-- 同一用户同一 client_id 只允许落库一次（超时重试不再插第二行提问）
CREATE TABLE ai_chat_client (
  household_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  client_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (household_id, user_id, client_id)
);

-- 空库导入占位行：并发两台设备同时 import 时先抢锁再写历史
CREATE TABLE ai_owner_lock (
  household_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  PRIMARY KEY (household_id, user_id)
);
