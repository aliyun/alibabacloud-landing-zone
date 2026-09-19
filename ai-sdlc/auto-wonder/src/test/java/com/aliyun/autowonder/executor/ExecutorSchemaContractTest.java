package com.aliyun.autowonder.executor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorSchemaContractTest {

    private static final Path CANONICAL_SCHEMA = Path.of("docs/autowonder-schema.sql");
    private static final Path LAUNCH_CONFIG_MIGRATION =
            Path.of("docs/migration/V056__executor_launch_config.sql");

    @Test
    void canonicalSchemaStoresTheLaunchConfigWithAnOptimisticLockVersion() throws Exception {
        String schema = Files.readString(CANONICAL_SCHEMA);

        // The database is the only source of the launch config, so both columns must stay in the canonical schema.
        assertTrue(schema.contains("`launch_config`  JSON            NULL"),
                "executor table must contain launch_config JSON NULL");
        assertTrue(schema.contains("`config_version` INT             NOT NULL DEFAULT 1"),
                "executor table must contain config_version INT NOT NULL DEFAULT 1");
    }

    @Test
    void launchConfigMigrationDefaultsTheVersionToOne() throws Exception {
        assertTrue(Files.exists(LAUNCH_CONFIG_MIGRATION), "V056 migration must exist");
        String sql = Files.readString(LAUNCH_CONFIG_MIGRATION);

        assertTrue(sql.contains("ADD COLUMN `launch_config` JSON NULL"),
                "migration must add launch_config as a nullable JSON column");
        // The insert statement omits config_version, so the first update can carry version=1 only if this holds.
        assertTrue(sql.contains("ADD COLUMN `config_version` INT NOT NULL DEFAULT 1"),
                "migration must add config_version with DEFAULT 1");
    }

    @Test
    void canonicalSchemaContainsLastConnectIpColumn() throws Exception {
        String schema = Files.readString(CANONICAL_SCHEMA);
        assertTrue(schema.contains("`last_connect_ip` VARCHAR(64)"),
                "executor table must contain last_connect_ip VARCHAR(64)");
    }

}
