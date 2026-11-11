-- Optional immutable snapshot of related work item links (new + existing duplicates).
-- Nullable on purpose: legacy notifications and unrelated types keep NULL, and no historical
-- row is backfilled, so old and new application versions can coexist during a rolling upgrade.
ALTER TABLE notification
    ADD COLUMN related_links_json TEXT NULL COMMENT 'Optional immutable related link snapshots' AFTER channels_json;
