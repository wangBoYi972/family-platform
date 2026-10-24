-- 邀请码：只有管理员可发放，注册时必须提供且校验通过才能创建账号。
-- 库中只保存不可逆哈希，明文仅在生成接口返回一次，管理员负责安全分发。
CREATE TABLE family_invite_code (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    household_id BIGINT NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    code_hint VARCHAR(8) NOT NULL,
    role VARCHAR(32) NOT NULL,
    max_uses INT NOT NULL DEFAULT 1,
    used_count INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at TIMESTAMP NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used_at TIMESTAMP NULL,
    used_phone VARCHAR(32) NULL,
    CONSTRAINT uk_invite_code_hash UNIQUE (code_hash),
    CONSTRAINT fk_invite_code_household FOREIGN KEY (household_id) REFERENCES family_household(id),
    CONSTRAINT ck_invite_code_role CHECK (role IN ('ADMIN', 'MEMBER')),
    CONSTRAINT ck_invite_code_uses CHECK (max_uses >= 1 AND used_count >= 0 AND used_count <= max_uses)
);

CREATE INDEX idx_invite_code_household ON family_invite_code (household_id);
