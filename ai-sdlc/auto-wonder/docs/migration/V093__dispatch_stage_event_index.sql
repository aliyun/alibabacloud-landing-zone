-- Stage duration queries read a few lifecycle events among many progress/tool events.
-- Filter the event type in the index before fetching event payload rows.
ALTER TABLE dispatch_runtime_event
  ADD INDEX idx_dispatch_event_type (tenant_id, dispatch_id, event_type),
  ALGORITHM=INPLACE, LOCK=NONE;
