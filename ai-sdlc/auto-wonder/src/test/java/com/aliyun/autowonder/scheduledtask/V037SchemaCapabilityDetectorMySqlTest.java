package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.scheduledtask.compat.V037MapperMode;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaCapability;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaCapabilityClassifier;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaCapabilityDetector;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaMode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real MySQL verification of the metadata shapes consumed by the startup detector. */
@Testcontainers(disabledWithoutDocker = true)
class V037SchemaCapabilityDetectorMySqlTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    private final ScheduledTaskIntegrationFixture fixture =
            new ScheduledTaskIntegrationFixture(MYSQL);

    @Test
    void acceptsCanonicalAndMigratedV037PhysicalMetadata() throws Exception {
        fixture.createDatabase("detector_canonical");
        try (Connection connection = fixture.open("detector_canonical")) {
            fixture.applyFile(connection, "docs/autowonder-schema.sql");
        }
        assertEquals(V037SchemaMode.V037_READY, detect("detector_canonical").mode());

        fixture.createDatabase("detector_migrated");
        try (Connection connection = fixture.open("detector_migrated")) {
            fixture.createPreV037LegacyTables(connection);
            fixture.applyFile(connection, "docs/migration/V041__scheduled_task.sql");
        }
        assertEquals(V037SchemaMode.V037_READY, detect("detector_migrated").mode());
    }

    @Test
    void rejectsIncompatibleSharedColumnDefinition() throws Exception {
        createCanonical("detector_bad_shared");
        execute("detector_bad_shared", "ALTER TABLE artifact MODIFY source_type "
                + "VARCHAR(64) NOT NULL DEFAULT 'WORKITEM'");

        assertEquals(V037SchemaMode.INCONSISTENT, detect("detector_bad_shared").mode());
    }

    @Test
    void detectsMissingPrimaryAndAutoIncrementAsScheduledIncomplete() throws Exception {
        createCanonical("detector_bad_primary");
        execute("detector_bad_primary", "ALTER TABLE scheduled_task "
                + "MODIFY id BIGINT UNSIGNED NOT NULL, DROP PRIMARY KEY");

        assertSourceAwarePartial(detect("detector_bad_primary"));
    }

    @Test
    void detectsMissingOnUpdateAsScheduledIncomplete() throws Exception {
        createCanonical("detector_bad_on_update");
        execute("detector_bad_on_update", "ALTER TABLE scheduled_task_run "
                + "MODIFY gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)");

        assertSourceAwarePartial(detect("detector_bad_on_update"));
    }

    @Test
    void detectsOrdinaryColumnInPlaceOfStoredGeneratedContract() throws Exception {
        createCanonical("detector_bad_generated");
        execute("detector_bad_generated", "ALTER TABLE dispatch "
                + "DROP INDEX uk_dispatch_normalized_idempotency, "
                + "DROP COLUMN normalized_idempotency_key, "
                + "ADD COLUMN normalized_idempotency_key VARCHAR(137) DEFAULT NULL AFTER idempotency_key, "
                + "ADD UNIQUE KEY uk_dispatch_normalized_idempotency "
                + "(tenant_id, normalized_idempotency_key)");

        assertSourceAwarePartial(detect("detector_bad_generated"));
    }

    @Test
    void incompleteSharedMigrationUsesTenantFirstIndexedEvidenceAcrossAllTenants() throws Exception {
        fixture.createDatabase("detector_partial_evidence");
        try (Connection connection = fixture.open("detector_partial_evidence")) {
            fixture.createPreV037LegacyTables(connection);
            execute(connection, "CREATE TABLE org (id BIGINT UNSIGNED NOT NULL, PRIMARY KEY(id)) ENGINE=InnoDB");
            execute(connection, "INSERT INTO org(id) VALUES (6), (7), (8)");
            fixture.applyFile(connection, "docs/migration/V041__scheduled_task.sql");
            execute(connection, "DROP TABLE scheduled_task_run, scheduled_task, workitem_comment_delivery");
        }

        V037SchemaCapability withoutEvidence = detect("detector_partial_evidence");
        assertEquals(V037SchemaMode.V037_PARTIAL, withoutEvidence.mode());
        assertEquals(V037MapperMode.LEGACY, withoutEvidence.mapperMode());

        execute("detector_partial_evidence",
                "UPDATE dispatch SET source_type = 'SCHEDULED_TASK_RUN' WHERE tenant_id = 7");

        assertEquals(V037SchemaMode.INCONSISTENT,
                detect("detector_partial_evidence").mode());
    }

    @Test
    void tenantFirstEvidenceQueryPlanUsesExactForcedIndexes() throws Exception {
        createCanonical("detector_evidence_explain");
        try (Connection connection = fixture.open("detector_evidence_explain");
             PreparedStatement statement = connection.prepareStatement("""
                     EXPLAIN SELECT 1
                       FROM org w FORCE INDEX (PRIMARY)
                       STRAIGHT_JOIN dispatch b FORCE INDEX (idx_dispatch_source)
                         ON b.tenant_id = w.id AND b.source_type = ?
                      LIMIT 1
                     """)) {
            statement.setString(1, "SCHEDULED_TASK_RUN");
            List<String> tables = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            List<String> accessTypes = new ArrayList<>();
            List<String> references = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    tables.add(resultSet.getString("table"));
                    keys.add(resultSet.getString("key"));
                    accessTypes.add(resultSet.getString("type"));
                    references.add(resultSet.getString("ref"));
                }
            }
            assertEquals(List.of("w", "b"), tables);
            assertEquals(List.of("PRIMARY", "idx_dispatch_source"), keys);
            assertEquals("ref", accessTypes.get(1));
            String[] businessReferences = references.get(1).split(",");
            assertEquals(2, businessReferences.length);
            assertTrue(businessReferences[0].endsWith(".w.id"), references.get(1));
            assertEquals("const", businessReferences[1]);
        }
    }

    private void createCanonical(String database) throws Exception {
        fixture.createDatabase(database);
        try (Connection connection = fixture.open(database)) {
            fixture.applyFile(connection, "docs/autowonder-schema.sql");
        }
    }

    private void execute(String database, String sql) throws Exception {
        try (Connection connection = fixture.open(database);
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private V037SchemaCapability detect(String database) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                        + "/" + database + "?useSSL=false",
                "root", MYSQL.getPassword());
        return new V037SchemaCapabilityDetector(
                new V037SchemaCapabilityClassifier()).detect(dataSource);
    }

    private void assertSourceAwarePartial(V037SchemaCapability capability) {
        assertEquals(V037SchemaMode.V037_PARTIAL, capability.mode(),
                capability.missingObjects().toString());
        assertEquals(V037MapperMode.SOURCE_AWARE, capability.mapperMode());
        assertFalse(capability.scheduledAvailable());
        assertTrue(capability.sourceAwareColumnsReady());
    }
}
