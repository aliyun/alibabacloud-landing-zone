CREATE TABLE IF NOT EXISTS dispatch_model_selection (
    tenant_id BIGINT NOT NULL,
    dispatch_id BIGINT NOT NULL,
    selection_json JSON NOT NULL,
    gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id, dispatch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
