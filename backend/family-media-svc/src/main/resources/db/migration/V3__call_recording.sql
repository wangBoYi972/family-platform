-- 通话录音与 AI 纪要。
--
-- 音频本体不进本库：客户端分段上传到文件服务，这里只保存 file_id 引用、转写稿与纪要。
-- 录音本身是隐私敏感内容，任何读取都受家庭归属与参与者身份双重约束。
ALTER TABLE call_session ADD COLUMN recording BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE call_session ADD COLUMN recorded_by BIGINT;

CREATE TABLE call_recording (
    id VARCHAR(36) PRIMARY KEY,
    call_id VARCHAR(36) NOT NULL,
    household_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    -- UPLOADED / TRANSCRIBING / READY / FAILED
    status VARCHAR(16) NOT NULL,
    duration_ms INT,
    recorded_by BIGINT NOT NULL,
    -- 录音是全员知情同意的：没有这个标记的提交一律拒绝
    consent_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    transcript TEXT,
    minutes TEXT,
    error VARCHAR(200),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 一通电话只留一份纪要：重复提交由唯一索引兜底，而不是靠应用层先查后插
CREATE UNIQUE INDEX uk_recording_call ON call_recording (call_id);
CREATE INDEX idx_recording_household ON call_recording (household_id, created_at);

CREATE TABLE call_recording_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recording_id VARCHAR(36) NOT NULL,
    seq INT NOT NULL,
    file_id VARCHAR(64) NOT NULL,
    duration_ms INT,
    transcript TEXT
);

-- 分段转写要按顺序拼接，seq 不能重复
CREATE UNIQUE INDEX uk_recording_segment_seq ON call_recording_segment (recording_id, seq);
