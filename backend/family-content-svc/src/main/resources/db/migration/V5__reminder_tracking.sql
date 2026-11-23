-- 离线提醒发送记录表：幂等控制,同一用户、同一业务对象、同一天只发一次
CREATE TABLE reminder_sent_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '接收人用户ID',
    business_type VARCHAR(32) NOT NULL COMMENT '业务类型: task / anniversary',
    business_id BIGINT NOT NULL COMMENT '业务对象ID',
    sent_date DATE NOT NULL COMMENT '发送日期(本地日期,非UTC)',
    result VARCHAR(16) NOT NULL COMMENT '发送结果: sent / skipped / no-quota / failed',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_dedupe (user_id, business_type, business_id, sent_date),
    INDEX idx_sent_date (sent_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='离线提醒发送记录';
