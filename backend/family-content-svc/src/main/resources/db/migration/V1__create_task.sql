CREATE TABLE family_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    household_id BIGINT NOT NULL,
    title VARCHAR(80) NOT NULL,
    assignee_name VARCHAR(64) NULL,
    deadline DATE NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    INDEX idx_task_household_created (household_id, created_at DESC),
    CONSTRAINT ck_task_status CHECK (status IN ('TODO', 'IN_PROGRESS', 'COMPLETED'))
);
