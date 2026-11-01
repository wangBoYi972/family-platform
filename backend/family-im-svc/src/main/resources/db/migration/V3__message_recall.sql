-- 消息撤回：软删标记。撤回时清空 body，仅保留占位（「此消息已撤回」）。
-- BOOLEAN 在 MySQL 里是 TINYINT(1)，H2 是原生 BOOLEAN，两端通用。
ALTER TABLE im_message ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
