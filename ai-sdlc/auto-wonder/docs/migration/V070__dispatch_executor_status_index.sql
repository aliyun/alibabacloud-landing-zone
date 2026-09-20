-- Supports executor-local slot accounting without scanning all dispatches.
ALTER TABLE dispatch
  ADD INDEX idx_dispatch_executor_status (executor_id, status, is_deleted);
