-- Selection is independent of enabled: disabling Feishu must not switch projects back to DingTalk.
CREATE TABLE IF NOT EXISTS platform_im_selection (
    id TINYINT NOT NULL PRIMARY KEY,
    provider VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO platform_im_selection (id, provider) VALUES (1, 'DINGTALK');
ALTER TABLE notify_pref ADD COLUMN feishu TINYINT NOT NULL DEFAULT 0;
