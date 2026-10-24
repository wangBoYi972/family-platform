-- 为 event_outbox 表添加发布状态和重试字段
ALTER TABLE event_outbox
ADD COLUMN published_at TIMESTAMP NULL COMMENT '发布时间',
ADD COLUMN error_message VARCHAR(500) NULL COMMENT '错误信息',
ADD COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
ADD INDEX idx_outbox_publish_status (published_at, created_at);
