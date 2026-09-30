package com.aliyun.autowonder.memory.store;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreDaoMappingTest {

    @Test
    void migrationPinsStoreDocumentAndImportIdentityConstraints() throws Exception {
        String migration = Files.readString(Path.of(
                "docs/migration/V073__server_backed_memory.sql"));

        assertAll(
                () -> assertTrue(migration.contains("UNIQUE KEY `uk_memory_store_owner` (`tenant_id`, `scope`, `owner_ref`)")),
                () -> assertTrue(migration.contains("UNIQUE KEY `uk_memory_document_path` (`store_id`, `path`)")),
                () -> assertTrue(migration.contains("UNIQUE KEY `uk_memory_change_revision` (`store_id`, `store_revision`)")),
                () -> assertTrue(migration.contains("UNIQUE KEY `uk_memory_change_idempotency` (`tenant_id`, `store_id`, `idempotency_key`)")),
                () -> assertTrue(migration.contains("UNIQUE KEY `uk_memory_import_source` (`tenant_id`, `agent_id`, `provider_family`, `logical_path`, `installation_fingerprint`)")),
                () -> assertFalse(migration.contains("`executor_id`, `provider_family`"),
                        "executor identity must not define a legacy memory source"),
                () -> assertTrue(migration.contains("COMMENT='Body-free append-only memory change log'")));
    }

    @Test
    void maintenanceLeaseIsCrossExecutorAndTimeBound() throws Exception {
        String migration = Files.readString(Path.of("docs/migration/V073__server_backed_memory.sql"));
        Configuration configuration = load("MemoryStoreDao.xml");
        String claim = sql(configuration, ns("MemoryStoreDao.claimMaintenance"), Map.of(
                "tenantId", 7L, "id", 3L, "owner", "dispatch:9",
                "dispatchId", 9L, "now", new java.util.Date(), "leaseUntil", new java.util.Date()));
        String reclaim = sql(configuration, ns("MemoryStoreDao.reclaimMaintenance"), Map.of(
                "tenantId", 7L, "id", 3L, "owner", "dispatch:9", "dispatchId", 9L,
                "now", new java.util.Date(), "leaseUntil", new java.util.Date()));
        assertAll(
                () -> assertTrue(migration.contains("maintenance_lease_owner")),
                () -> assertTrue(migration.contains("maintenance_lease_until")),
                () -> assertTrue(claim.contains("maintenance_lease_until < ?"), claim),
                () -> assertFalse(claim.contains("OR maintenance_lease_owner = ?"), claim),
                () -> assertTrue(reclaim.contains("maintenance_lease_dispatch_id = ?"), reclaim),
                () -> assertTrue(configuration.hasStatement(ns("MemoryStoreDao.renewMaintenance"))),
                () -> assertTrue(configuration.hasStatement(ns("MemoryStoreDao.lockActiveMaintenanceOwner"))),
                () -> assertTrue(migration.contains("curation_lease_id")));
    }

    @Test
    void everyMapperLoadsAndExposesItsCoreStatements() throws Exception {
        Configuration configuration = load(
                "MemoryStoreDao.xml",
                "MemoryDocumentDao.xml",
                "MemoryChangeDao.xml",
                "MemoryStoreAclDao.xml",
                "MemoryImportDao.xml");

        for (String id : List.of(
                "MemoryStoreDao.insert", "MemoryStoreDao.findActive", "MemoryStoreDao.claimMaintenance",
                "MemoryDocumentDao.insert", "MemoryDocumentDao.insertEmptyIndexIfAbsent",
                "MemoryDocumentDao.update", "MemoryDocumentDao.eraseAndDelete",
                "MemoryChangeDao.insert", "MemoryChangeDao.listAfterRevision",
                "MemoryStoreAclDao.insert", "MemoryStoreAclDao.listByStore",
                "MemoryImportDao.upsertSource", "MemoryImportDao.insertSnapshot",
                "MemoryImportDao.deleteSnapshotsBeforeLatestTwo", "MemoryImportDao.findSuccessfulReceiptByHash")) {
            assertTrue(configuration.hasStatement(ns(id)), () -> "missing mapped statement " + id);
        }
    }

    @Test
    void documentUpdateAndDeleteUseTenantAndOptimisticVersion() throws Exception {
        Configuration configuration = load("MemoryDocumentDao.xml");
        Map<String, Object> args = new HashMap<>();
        args.put("id", 3L);
        args.put("tenantId", 7L);
        args.put("storeId", 11L);
        args.put("path", "feedback_testing.md");
        args.put("contentMd", "body");
        args.put("contentSha256", "abc");
        args.put("byteSize", 4L);
        args.put("version", 9);
        args.put("modifierId", 22L);

        String update = sql(configuration, ns("MemoryDocumentDao.update"), args);
        String delete = sql(configuration, ns("MemoryDocumentDao.eraseAndDelete"), args);

        assertAll(
                () -> assertTrue(update.contains("tenant_id = ?"), update),
                () -> assertTrue(update.contains("store_id = ?"), update),
                () -> assertTrue(update.contains("version = ?"), update),
                () -> assertTrue(delete.contains("tenant_id = ?"), delete),
                () -> assertTrue(delete.contains("store_id = ?"), delete),
                () -> assertTrue(delete.contains("version = ?"), delete),
                () -> assertTrue(delete.contains("content_md = ''"), delete),
                () -> assertTrue(delete.contains("content_sha256 = ''"), delete));
    }

    @Test
    void changeRowsAreAppendOnlyAndSnapshotRetentionIsBounded() throws Exception {
        Configuration configuration = load("MemoryChangeDao.xml", "MemoryImportDao.xml");
        String changeInsert = sql(configuration, ns("MemoryChangeDao.insert"), new MemoryChangeDO());
        String retention = sql(configuration, ns("MemoryImportDao.deleteSnapshotsBeforeLatestTwo"),
                Map.of("tenantId", 7L, "sourceId", 8L));

        assertTrue(changeInsert.startsWith("INSERT INTO memory_change"), changeInsert);
        assertFalse(configuration.hasStatement(ns("MemoryChangeDao.update")));
        assertTrue(retention.contains("OFFSET 2"), retention);
        assertTrue(retention.contains("tenant_id = ?"), retention);
        assertTrue(retention.contains("source_id = ?"), retention);
    }

    private Configuration load(String... files) throws Exception {
        Configuration configuration = new Configuration();
        for (String file : files) {
            String resource = "/mapping/" + file;
            try (InputStream in = getClass().getResourceAsStream(resource)) {
                assertNotNull(in, resource + " must be on the classpath");
                new XMLMapperBuilder(in, configuration, "mapping/" + file,
                        configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    private String sql(Configuration configuration, String id, Object args) {
        BoundSql boundSql = configuration.getMappedStatement(id).getBoundSql(args);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }

    private String ns(String suffix) {
        return "com.aliyun.autowonder.memory.store." + suffix;
    }
}
