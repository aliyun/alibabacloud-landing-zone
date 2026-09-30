package com.aliyun.autowonder.memory.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySchemaContractTest {
    @Test
    void canonicalFreshInstallSchemaContainsCompleteMemoryStore() throws Exception {
        String schema = Files.readString(Path.of("docs/autowonder-schema.sql"));
        for (String table : new String[]{"memory_store", "memory_document", "memory_change",
                "memory_store_acl", "memory_import_source", "memory_import_snapshot",
                "memory_import_receipt"}) {
            assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS `" + table + "`"),
                    () -> "canonical schema must create " + table);
        }
        for (String leaseColumn : new String[]{"maintenance_lease_owner", "maintenance_lease_until",
                "maintenance_lease_dispatch_id", "curation_lease_id", "curation_lease_until"}) {
            assertTrue(schema.contains("`" + leaseColumn + "`"),
                    () -> "canonical schema must contain " + leaseColumn);
        }
        assertTrue(schema.contains("`request_fingerprint` CHAR(64)"));
    }
}
