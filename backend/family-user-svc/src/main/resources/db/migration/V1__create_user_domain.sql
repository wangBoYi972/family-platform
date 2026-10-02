CREATE TABLE family_household (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE family_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    phone VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE family_white_list (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    phone VARCHAR(32) NOT NULL UNIQUE,
    household_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_white_list_household FOREIGN KEY (household_id) REFERENCES family_household(id),
    CONSTRAINT ck_white_list_role CHECK (role IN ('ADMIN', 'MEMBER'))
);

CREATE TABLE family_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    household_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_member_household_user (household_id, user_id),
    CONSTRAINT fk_member_household FOREIGN KEY (household_id) REFERENCES family_household(id),
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES family_user(id)
);
