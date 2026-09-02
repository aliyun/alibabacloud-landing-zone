ALTER TABLE dispatch_ai_usage
    ADD COLUMN step_id VARCHAR(64) NOT NULL DEFAULT '' AFTER artifact_id;

-- Change unique key to include step_id for per-step granularity.
-- Old key: uk_dispatch_provider_model (tenant_id, dispatch_id, provider, model)
-- New key: uk_dispatch_step_provider_model (tenant_id, dispatch_id, step_id, provider, model)
-- Using NOT NULL DEFAULT '' avoids MySQL NULL-uniqueness issue (NULL != NULL in UNIQUE).
ALTER TABLE dispatch_ai_usage
    DROP INDEX uk_dispatch_provider_model,
    ADD UNIQUE KEY uk_dispatch_step_provider_model (tenant_id, dispatch_id, step_id, provider, model);
