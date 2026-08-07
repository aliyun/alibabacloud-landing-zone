-- V037: Indexes for human-agent participation lifecycle reconstruction
-- Composite event index for ordered per-workitem event scan
CREATE INDEX idx_workitem_event_participation
  ON workitem_event (tenant_id, workitem_id, event_type, gmt_create, id);

-- Status-node lookup index for terminal status resolution
CREATE INDEX idx_status_node_participation
  ON status_node (template_id, code, category);
