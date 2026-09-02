-- Record when a workitem's planned scheduled start actually fired, so list and
-- detail views can keep the scheduled-execution badge after the schedule clears.
ALTER TABLE workitem
  ADD COLUMN scheduled_start_triggered_at DATETIME(3) NULL DEFAULT NULL COMMENT '定时执行实际触发时间，NULL表示尚未触发';
