-- Supports the 90d pipeline-health aggregates (success rate / rework / average duration)
-- without scanning every dispatch row of a tenant. Index only; no table shape change.
ALTER TABLE dispatch
  ADD INDEX idx_dispatch_formal_created (tenant_id, source_type, gmt_create, status);
