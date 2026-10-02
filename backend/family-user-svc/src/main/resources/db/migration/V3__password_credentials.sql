CREATE TABLE user_credential (
    phone VARCHAR(32) PRIMARY KEY,
    password_hash VARCHAR(100) NOT NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP NULL,
    changed_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_credential_whitelist FOREIGN KEY (phone) REFERENCES family_white_list(phone)
);

CREATE TABLE platform_initialization (
    id INT PRIMARY KEY,
    completed BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO platform_initialization (id, completed) VALUES (1, FALSE);
