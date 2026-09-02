-- Workitem scheduled execution (scheduled_start_at) and tags extension fields.
ALTER TABLE workitem
  ADD COLUMN scheduled_start_at DATETIME(3) NULL DEFAULT NULL COMMENT '计划执行时间，NULL表示立即执行',
  ADD COLUMN tags JSON NULL DEFAULT NULL COMMENT '工单标签数组';

-- MySQL has no partial index; composite index narrows the scanner's due-query scan.
CREATE INDEX idx_workitem_scheduled ON workitem (tenant_id, scheduled_start_at, assignee_type, sdlc_id);
