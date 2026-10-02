CREATE TABLE IF NOT EXISTS event_outbox (
  id VARCHAR(36) PRIMARY KEY,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id VARCHAR(128) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  household_id BIGINT NOT NULL,
  payload TEXT NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP NOT NULL,
  locked_at TIMESTAMP NULL,
  last_error VARCHAR(1000) NULL,
  created_at TIMESTAMP NOT NULL,
  published_at TIMESTAMP NULL,
  INDEX ix_event_outbox_pending (status, next_attempt_at, created_at)
);
