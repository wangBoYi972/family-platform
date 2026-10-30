-- 实时通话：会话 + 参与者。
-- SDP / ICE 候选只存在于信令通道与客户端内存中，绝不落库，避免隐私与体积问题。
DROP TABLE IF EXISTS media_session;

CREATE TABLE call_session (
    id VARCHAR(36) PRIMARY KEY,
    household_id BIGINT NOT NULL,
    mode VARCHAR(8) NOT NULL,
    kind VARCHAR(8) NOT NULL,
    status VARCHAR(12) NOT NULL,
    caller_id BIGINT NOT NULL,
    end_reason VARCHAR(16),
    created_at TIMESTAMP NOT NULL,
    answered_at TIMESTAMP,
    ended_at TIMESTAMP
);

CREATE INDEX idx_call_household_created ON call_session (household_id, created_at);

CREATE TABLE call_participant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    call_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    state VARCHAR(12) NOT NULL,
    mic_on BOOLEAN NOT NULL DEFAULT TRUE,
    cam_on BOOLEAN NOT NULL DEFAULT TRUE,
    invited_at TIMESTAMP NOT NULL,
    joined_at TIMESTAMP,
    left_at TIMESTAMP
);

CREATE UNIQUE INDEX uk_call_participant ON call_participant (call_id, user_id);
CREATE INDEX idx_participant_user ON call_participant (user_id, state);
