ALTER TABLE executor ADD COLUMN last_started_at DATETIME(3) NULL COMMENT '客户端进程启动时间' AFTER last_heartbeat;
