-- Apply before deploying the modified-time comment poller.
-- NULL performs an initial scan from today's midnight (Asia/Shanghai), with overlap.
ALTER TABLE `external_project_binding`
  ADD COLUMN `comment_poll_watermark` DATETIME(3) DEFAULT NULL
    COMMENT '独立评论链路已完整扫描的修改时间上界' AFTER `reconcile_cursor`;
