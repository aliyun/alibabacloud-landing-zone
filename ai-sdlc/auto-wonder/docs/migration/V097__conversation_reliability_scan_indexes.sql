-- Additive indexes for bounded conversation history pages and fair recovery scans.
-- Keep existing replay/status indexes for old clients and existing query patterns.
ALTER TABLE agent_conversation_turn_event
  ADD INDEX idx_turn_event_page (tenant_id, conversation_id, turn_id, id);
ALTER TABLE agent_conversation_turn
  ADD INDEX idx_turn_recovery_cursor (status, direction, id);
