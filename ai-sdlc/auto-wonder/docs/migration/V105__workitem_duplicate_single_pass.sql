-- 工单同意图检测：单次全量标题分析（优化 #56680）。
-- 仅加法/兼容变更，不改写 V075 历史迁移，不删除历史终态记录。
-- MySQL 不支持 ADD COLUMN IF NOT EXISTS，按 information_schema 判存在后执行，可安全重复执行。

-- 1. 固定候选快照：首次查询后持久化，之后不可替换。
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workitem_duplicate_check'
      AND COLUMN_NAME = 'candidate_snapshot_json') = 0,
  'ALTER TABLE `workitem_duplicate_check` ADD COLUMN `candidate_snapshot_json` MEDIUMTEXT DEFAULT NULL COMMENT ''固定候选快照 [{"id":1,"title":".."}]'' AFTER `candidate_total`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. 超时后待尽力取消的模型会话标记；取消不等待 ACK。
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workitem_duplicate_check'
      AND COLUMN_NAME = 'cancel_pending') = 0,
  'ALTER TABLE `workitem_duplicate_check` ADD COLUMN `cancel_pending` TINYINT NOT NULL DEFAULT 0 COMMENT ''1=待尽力取消模型会话'' AFTER `error`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. 结果 JSON 与应用侧 65,536 字节输出上限对齐，避免 TEXT 写不下。
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workitem_duplicate_check'
      AND COLUMN_NAME = 'match_json' AND DATA_TYPE <> 'mediumtext') > 0,
  'ALTER TABLE `workitem_duplicate_check` MODIFY COLUMN `match_json` MEDIUMTEXT DEFAULT NULL COMMENT ''[{"id":..,"reason":..}]''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4. 任务标题与工单标题上限对齐（工单标题 256）。
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workitem_duplicate_check'
      AND COLUMN_NAME = 'title' AND CHARACTER_MAXIMUM_LENGTH < 256) > 0,
  'ALTER TABLE `workitem_duplicate_check` MODIFY COLUMN `title` VARCHAR(256) NOT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5. 按稳定 requestId 关联会话/结果，并支持检测会话识别的反向查询。
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workitem_duplicate_check'
      AND INDEX_NAME = 'idx_duplicate_check_request') = 0,
  'ALTER TABLE `workitem_duplicate_check` ADD KEY `idx_duplicate_check_request` (`tenant_id`, `request_id`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
