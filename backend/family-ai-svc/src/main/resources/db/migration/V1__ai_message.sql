CREATE TABLE ai_message (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 household_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 role VARCHAR(20) NOT NULL,
 content VARCHAR(8000) NOT NULL,
 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 INDEX idx_ai_message_owner (household_id, user_id, id)
);
