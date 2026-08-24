package com.aliyun.autowonder.integration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalCollaborationSchemaContractTest {

    @Test
    void migrationUsesProviderNeutralPrincipalRelations() throws Exception {
        String migration = Files.readString(Path.of(
                "docs/migration/V038__external_workitem_collaboration.sql"));
        String canonicalSchema = Files.readString(Path.of("docs/autowonder-schema.sql"));

        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS `external_principal`"));
        assertTrue(migration.contains("UNIQUE KEY `uk_external_principal` (`provider`, `subject_id`)"));
        assertFalse(migration.contains("`realm_id`"));
        assertFalse(migration.contains("`mapped_user_id`"));
        assertTrue(migration.contains("`principal_relations_json` JSON"));
        assertTrue(migration.contains("`reporter_principal_id`"));
        assertTrue(migration.contains("`business_owner_principal_id`"));
        assertTrue(migration.contains("ADD UNIQUE KEY `uk_external_workitem_scope`"));
        assertTrue(migration.contains("ADD UNIQUE KEY `uk_external_comment_scope`"));
        assertFalse(migration.contains("participant_principal_ids_json"));
        assertFalse(migration.contains("watcher_principal_ids_json"));

        assertTrue(canonicalSchema.contains("CREATE TABLE IF NOT EXISTS `external_principal`"));
        assertTrue(canonicalSchema.contains("`principal_relations_json` JSON"));
    }
}
