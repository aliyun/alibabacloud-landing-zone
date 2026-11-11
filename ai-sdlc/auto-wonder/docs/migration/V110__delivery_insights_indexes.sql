-- Apply before enabling delivery analytics. Additive only; no data rewrite.
-- Run once via the normal migration procedure. Existing indexes are retained.
ALTER TABLE workitem_event ADD INDEX idx_delivery_insight_event (tenant_id,event_type,gmt_create,workitem_id);
ALTER TABLE dispatch ADD INDEX idx_delivery_insight_modified (tenant_id,source_type,gmt_modified,id);
