-- 用药与复诊：健康正文统一加密入库，时间与可见性字段保留明文供查询。
CREATE TABLE medication_plan (
 id VARCHAR(36) PRIMARY KEY,
 household_id BIGINT NOT NULL,
 owner_id BIGINT NOT NULL,
 encrypted_body TEXT NOT NULL,
 dose_times VARCHAR(120) NOT NULL,
 start_date DATE NOT NULL,
 end_date DATE NOT NULL,
 visibility VARCHAR(16) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX idx_medication_family (household_id, owner_id, end_date)
);

-- 同一天同一时刻重复勾选只保留一行，幂等键落在数据库层。
CREATE TABLE medication_check (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 household_id BIGINT NOT NULL,
 plan_id VARCHAR(36) NOT NULL,
 check_date DATE NOT NULL,
 dose_time VARCHAR(5) NOT NULL,
 checked_by BIGINT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_medication_check UNIQUE (household_id, plan_id, check_date, dose_time),
 INDEX idx_medication_check_plan (plan_id, check_date)
);

CREATE TABLE follow_up_visit (
 id VARCHAR(36) PRIMARY KEY,
 household_id BIGINT NOT NULL,
 owner_id BIGINT NOT NULL,
 encrypted_body TEXT NOT NULL,
 visit_at TIMESTAMP NOT NULL,
 remind_days INT NOT NULL DEFAULT 0,
 visibility VARCHAR(16) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX idx_follow_up_family (household_id, owner_id, visit_at)
);
