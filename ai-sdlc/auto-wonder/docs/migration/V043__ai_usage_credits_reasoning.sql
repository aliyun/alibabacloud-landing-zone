ALTER TABLE dispatch_ai_usage
    ADD COLUMN reasoning_tokens BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER cache_write_tokens,
    ADD COLUMN credits DECIMAL(12, 4) DEFAULT NULL AFTER reasoning_tokens;
