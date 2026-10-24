-- 创建内容-文件关联表,替代 VARCHAR 存储文件ID列表
CREATE TABLE content_file_mapping (
    content_type VARCHAR(16) NOT NULL COMMENT '内容类型: TASK, NOTICE, CALENDAR, DYNAMIC, ALBUM',
    content_id BIGINT NOT NULL COMMENT '内容ID',
    file_id VARCHAR(64) NOT NULL COMMENT '文件ID',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序顺序',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (content_type, content_id, file_id),
    INDEX idx_file_lookup (file_id)
) COMMENT '内容文件关联表,规范化存储文件关联关系';
