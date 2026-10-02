CREATE TABLE auth_session (
 id VARCHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 household_id BIGINT NOT NULL,
 role VARCHAR(32) NOT NULL,
 client VARCHAR(16) NOT NULL,
 expires_at TIMESTAMP NOT NULL,
 revoked BOOLEAN NOT NULL DEFAULT FALSE,
 INDEX idx_auth_user (user_id)
);
