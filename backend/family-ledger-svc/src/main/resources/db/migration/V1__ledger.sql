-- 家庭账本：金额一律以整数分存储，禁止浮点；家庭编号永远由已校验会话下发。
CREATE TABLE ledger_entry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    household_id BIGINT NOT NULL,
    type VARCHAR(16) NOT NULL,
    amount_cents BIGINT NOT NULL,
    category VARCHAR(32) NOT NULL,
    note VARCHAR(200) NULL,
    occurred_on DATE NOT NULL,
    created_by BIGINT NOT NULL,
    -- 支出时是垫付人，收入时是收款人；同一列两种语义，列表与汇总按 type 解释。
    payer_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    INDEX idx_ledger_entry_household_date (household_id, occurred_on DESC),
    INDEX idx_ledger_entry_household_payer (household_id, payer_id),
    CONSTRAINT ck_ledger_entry_type CHECK (type IN ('EXPENSE', 'INCOME')),
    CONSTRAINT ck_ledger_entry_amount CHECK (amount_cents > 0)
);

CREATE TABLE ledger_split (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    entry_id BIGINT NOT NULL,
    household_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    amount_cents BIGINT NOT NULL,
    UNIQUE KEY uk_ledger_split_entry_member (entry_id, member_id),
    INDEX idx_ledger_split_household_entry (household_id, entry_id),
    CONSTRAINT ck_ledger_split_amount CHECK (amount_cents > 0),
    CONSTRAINT fk_ledger_split_entry FOREIGN KEY (entry_id) REFERENCES ledger_entry(id) ON DELETE CASCADE
);
