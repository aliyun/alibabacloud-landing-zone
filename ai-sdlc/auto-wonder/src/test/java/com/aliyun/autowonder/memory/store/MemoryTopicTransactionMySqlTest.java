package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.memory.store.dto.MemoryTopicRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class MemoryTopicTransactionMySqlTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("memory_topics").withUsername("test").withPassword("test")
            .withCommand("--max-allowed-packet=16M");
    static DataSource ds;
    static JdbcTemplate jdbc;
    static MemoryTopicService service;
    static MemoryStoreDao stores;
    static MemoryDocumentDao documents;
    static com.aliyun.autowonder.memory.MemoryDao legacy;
    static LegacyMemoryMigrationService migration;

    @BeforeAll static void setup() throws Exception {
        ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        try (var c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c, new FileSystemResource("docs/migration/V073__server_backed_memory.sql"));
            String schema = java.nio.file.Files.readString(java.nio.file.Path.of("docs/autowonder-schema.sql"));
            int start = schema.indexOf("CREATE TABLE IF NOT EXISTS `memory` (");
            jdbc.execute(schema.substring(start, schema.indexOf(';', start) + 1));
        }
        var bean = new SqlSessionFactoryBean();
        bean.setDataSource(ds);
        var config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(config);
        bean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapping/Memory*Dao.xml"));
        var session = new SqlSessionTemplate(bean.getObject());
        stores = session.getMapper(MemoryStoreDao.class);
        documents = session.getMapper(MemoryDocumentDao.class);
        var changes = session.getMapper(MemoryChangeDao.class);
        legacy = session.getMapper(com.aliyun.autowonder.memory.MemoryDao.class);
        var acls = session.getMapper(MemoryStoreAclDao.class);
        var agents = mock(AgentDao.class);
        var agent = new AgentDO(); agent.setId(42L); agent.setTenantId(7L); agent.setName("发布工程师");
        when(agents.findById(42L)).thenReturn(agent);
        var documentService = tx(new MemoryDocumentService(stores, documents, changes));
        var squads = mock(SquadDao.class);
        var squad = new com.aliyun.autowonder.squad.SquadDO(); squad.setId(12L); squad.setTenantId(7L); squad.setName("发布小队");
        when(squads.findById(12L)).thenReturn(squad);
        service = tx(new MemoryTopicService(stores, documents, changes, acls,
                documentService,
                new MemoryStoreAccessService(), agents, squads));
        migration = tx(new LegacyMemoryMigrationService(legacy, changes, service));
    }

    @SuppressWarnings("unchecked") static <T> T tx(T target) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM memory");
        jdbc.update("DELETE FROM memory_change"); jdbc.update("DELETE FROM memory_document");
        jdbc.update("DELETE FROM memory_store_acl"); jdbc.update("DELETE FROM memory_store");
    }

    void seedLegacy(long id, long tenant, String scope, Long owner, String status, String body) {
        jdbc.update("INSERT INTO memory (id,tenant_id,scope,owner_ref,type,title,content_md,status,creator_id) VALUES (?,?,?,?,?,?,?,?,9)",
                id, tenant, scope, owner, "工程经验", "历史发布约定", body, status);
    }

    @Test void concurrentLegacyEditCannotLeaveMigratedBodyStale() throws Exception {
        legacyMutationRacingMigration(false);
    }

    @Test void concurrentLegacyDeleteCannotLeaveMigratedTopicAlive() throws Exception {
        legacyMutationRacingMigration(true);
    }

    private void legacyMutationRacingMigration(boolean delete) throws Exception {
        seedLegacy(1, 7, "AGENT", 42L, "ADOPTED", "Original body");
        var migrationResult = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var intercepted = mock(com.aliyun.autowonder.memory.MemoryDao.class, invocation -> {
                Object result;
                try { result = invocation.getMethod().invoke(legacy, invocation.getArguments()); }
                catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                if (invocation.getMethod().getName().equals("isMigrated")) {
                    var started = new java.util.concurrent.CountDownLatch(1);
                    var future = executor.submit(() -> { started.countDown(); return migration.migrateOne(1); });
                    migrationResult.set(future);
                    assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    // Give migration a chance to commit after the eligibility check.
                    // With the fix it must instead wait for this source-row lock.
                    try { future.get(1, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (java.util.concurrent.TimeoutException expectedWhileLocked) { }
                }
                return result;
            });
            var oldService = tx(new com.aliyun.autowonder.memory.MemoryService(intercepted,
                    mock(com.aliyun.autowonder.memory.MemoryReviewDao.class),
                    mock(com.aliyun.autowonder.agent.AgentMemoryRefDao.class), null, null));
            if (delete) oldService.delete(1, 7, 9);
            else {
                var request = new com.aliyun.autowonder.memory.dto.UpdateMemoryRequest();
                request.setContentMd("Updated body");
                oldService.update(1, request, 7, 9);
            }
            boolean migrated = migrationResult.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (delete) {
                assertFalse(migrated, "a deleted legacy source must not remain alive in the new store");
                assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM memory_document", Long.class));
            } else {
                assertTrue(migrated);
                var store = stores.findActive(7L, "AGENT", 42L);
                assertEquals("Updated body", MemoryTopicFormat.parse(
                        documents.findByPath(7L, store.getId(), "legacy_1.md", false).getContentMd()).body());
            }
        }
    }

    @Test void startupBatchMigratesAcrossSpacesWithoutDispatchAndKeepsBody() {
        seedLegacy(1, 7, "AGENT", 42L, "ADOPTED", "  原始正文\n\n");
        seedLegacy(2, 8, "ORG", null, "ADOPTED", "跨空间共享约定");
        seedLegacy(3, 7, "AGENT", 42L, "PENDING", "未采纳");
        seedLegacy(4, 7, "AGENT", 42L, "REJECTED", "已拒绝");
        seedLegacy(5, 7, "AGENT", 42L, "ADOPTED", "已删除");
        jdbc.update("UPDATE memory SET is_deleted=1 WHERE id=5");
        var task = new LegacyMemoryMigrationTask(legacy, migration);
        task.onReady(); task.migrateBatch();
        var store = stores.findActive(7L, "AGENT", 42L);
        var topic = documents.findByPath(7L, store.getId(), "legacy_1.md", false);
        assertEquals("  原始正文\n\n", MemoryTopicFormat.parse(topic.getContentMd()).body());
        assertEquals("工程经验", MemoryTopicFormat.parse(topic.getContentMd()).metadata().get("legacy_type"));
        assertNotNull(stores.findActive(8L, "ORG", 0L));
        assertEquals(4L, jdbc.queryForObject("SELECT COUNT(*) FROM memory_document", Long.class));
        assertEquals(0, legacy.listAdoptedForMigration(0, 100).size());
        assertTrue(legacy.isMigrated(7, 1));
        assertFalse(legacy.isMigrated(8, 1));
        assertEquals(1, migration.progress(7).migrated());
        assertEquals(0, migration.progress(7).pending());
    }

    @Test void migratedDeletionAndArchivedStoreDoNotReviveSourceOnRescan() {
        seedLegacy(1, 7, "AGENT", 42L, "ADOPTED", "个人旧记忆");
        seedLegacy(2, 7, "ORG", null, "ADOPTED", "组织旧记忆");
        assertTrue(migration.migrateOne(1)); assertTrue(migration.migrateOne(2));
        var store = stores.findActive(7L, "AGENT", 42L);
        var topic = documents.findByPath(7L, store.getId(), "legacy_1.md", false);
        service.deleteForUser(7, 9, true, store.getId(), topic.getPath(), topic.getVersion(), "learned-delete");
        jdbc.update("UPDATE memory_store SET status='ARCHIVED' WHERE scope='ORG'");
        assertFalse(migration.migrateOne(1)); assertFalse(migration.migrateOne(2));
        assertEquals(1, documents.listLive(7L, store.getId()).size());
        assertEquals(0, legacy.listAdoptedForMigration(0, 100).size());
    }

    @Test void badOwnerDoesNotStarveLaterSourceAndCanBeRetried() {
        seedLegacy(1, 7, "AGENT", 99L, "ADOPTED", "等待归属修复");
        seedLegacy(2, 8, "ORG", null, "ADOPTED", "有效的后续记录");
        var task = new LegacyMemoryMigrationTask(legacy, migration);
        task.onReady(); task.migrateBatch();
        assertEquals(1, migration.progress(7).pending());
        assertEquals(1, migration.progress(8).migrated());
        jdbc.update("UPDATE memory SET owner_ref=42 WHERE id=1");
        task.migrateBatch();
        assertEquals(1, migration.progress(7).migrated());
        assertEquals(0, migration.progress(7).pending());
    }

    @Test void concurrentMigrationHasOneDurableReceiptAndCorrectSquadGrant() {
        seedLegacy(1, 7, "SQUAD", 12L, "ADOPTED", "小队共享经验");
        var one = java.util.concurrent.CompletableFuture.supplyAsync(() -> migration.migrateOne(1));
        var two = java.util.concurrent.CompletableFuture.supplyAsync(() -> migration.migrateOne(1));
        assertNotEquals(one.join(), two.join());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM memory_change WHERE actor_type='LEGACY_MIGRATION' AND path='legacy_1.md'", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM memory_store_acl WHERE subject_type='SQUAD' AND subject_ref='12' AND permission='READ'", Long.class));
    }

    @Test void failedMigrationIsNotMarkedCompleteAndRollsBackPartialTopic() {
        var existing = service.createForUser(7, 9, true, "AGENT", 42, request("before"));
        jdbc.update("UPDATE memory_document SET content_md='broken index' WHERE path='MEMORY.md'");
        seedLegacy(1, 7, "AGENT", 42L, "ADOPTED", "不能丢失的源数据");
        assertThrows(IllegalArgumentException.class, () -> migration.migrateOne(1));
        assertFalse(legacy.isMigrated(7, 1));
        assertNull(documents.findByPath(7L, existing.getStoreId(), "legacy_1.md", false));
        assertEquals("不能丢失的源数据", legacy.findById(1L).getContentMd());
    }

    @Test void overSizeMigrationRemainsPendingWithoutWeakeningDocumentLimit() {
        seedLegacy(1, 7, "AGENT", 42L, "ADOPTED", "x".repeat(MemoryDocumentValidator.MAX_DOCUMENT_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> migration.migrateOne(1));
        assertEquals(1, migration.progress(7).pending());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM memory_document", Long.class));
    }

    static MemoryTopicRequest request(String key) {
        return new MemoryTopicRequest(null, "feedback", "发布约定", "社区同步时参考", "保留独特上下文。\n", null, key);
    }

    @Test void createRetryEditDeleteKeepIndexAndBodyConsistent() {
        var created = service.createForUser(7, 9, true, "AGENT", 42, request("first"));
        var repeated = service.createForUser(7, 9, true, "AGENT", 42, request("first"));
        assertEquals(created.getId(), repeated.getId());
        assertEquals(2, documents.listLive(7L, created.getStoreId()).size());
        var update = new MemoryTopicRequest(created.getPath(), "feedback", "修订约定", "新的适用条件", "更新内容。\n", created.getVersion(), "edit");
        var edited = service.updateForUser(7, 9, true, created.getStoreId(), update);
        assertTrue(documents.findByPath(7L, created.getStoreId(), "MEMORY.md", false).getContentMd().contains("修订约定"));
        assertThrows(MemoryDocumentService.MemoryConflictException.class, () -> service.updateForUser(7, 9, true,
                created.getStoreId(), new MemoryTopicRequest(created.getPath(), "feedback", "过时修改", "旧", "旧正文", created.getVersion(), "stale")));
        service.deleteForUser(7, 9, true, created.getStoreId(), created.getPath(), edited.getVersion(), "delete");
        service.deleteForUser(7, 9, true, created.getStoreId(), created.getPath(), edited.getVersion(), "delete");
        assertEquals(1, documents.listLive(7L, created.getStoreId()).size());
        assertEquals("", documents.findByPath(7L, created.getStoreId(), "MEMORY.md", false).getContentMd());
        service.createForUser(7, 9, true, "AGENT", 42, request("first"));
        assertEquals(1, documents.listLive(7L, created.getStoreId()).size(), "response retry must not revive deleted content");
    }

    @Test void indexFailureRollsBackTopicAndChange() {
        var first = service.createForUser(7, 9, true, "AGENT", 42, request("first"));
        jdbc.update("UPDATE memory_document SET content_md='invalid index' WHERE store_id=? AND path='MEMORY.md'", first.getStoreId());
        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM memory_change", Long.class);
        assertThrows(IllegalArgumentException.class, () -> service.createForUser(7, 9, true, "AGENT", 42, request("second")));
        assertEquals(2, documents.listLive(7L, first.getStoreId()).size());
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM memory_change", Long.class));
    }

    @Test void sharedDefaultsAreNotRestoredAfterAdminRemovesThem() {
        var first = service.createForUser(7, 9, true, "ORG", 0, request("org1"));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM memory_store_acl WHERE store_id=? AND subject_type='ROLE' AND subject_ref='ALL_AGENTS' AND permission='READ'", Long.class, first.getStoreId()));
        jdbc.update("DELETE FROM memory_store_acl WHERE store_id=?", first.getStoreId());
        service.createForUser(7, 9, true, "ORG", 0, request("org2"));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM memory_store_acl", Long.class));
    }

    @Test void unknownAndCrossTenantOwnersCannotCreateStores() {
        assertThrows(IllegalArgumentException.class, () -> service.createForUser(8, 9, true, "AGENT", 42, request("bad")));
        assertThrows(IllegalArgumentException.class, () -> service.createForUser(7, 9, true, "AGENT", 99, request("bad2")));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM memory_store", Long.class));
        assertThrows(MemoryStoreApplicationService.MemoryAccessDeniedException.class,
                () -> service.createForUser(7, 9, false, "AGENT", 42, request("unauthorized")));
    }

    @Test void humanHttpRequestCreatesRealTopicAndIndex() throws Exception {
        var context = com.aliyun.autowonder.context.AutoWonderContext.get();
        context.setCurrentWorkspaceId(7L); context.setUserId(9L);
        context.setWorkspaceAccessLevel(com.aliyun.autowonder.access.WorkspaceAccessLevel.ADMIN);
        try {
            var http = org.springframework.test.web.servlet.setup.MockMvcBuilders
                    .standaloneSetup(new MemoryTopicController(service)).build();
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/memory-stores/topics")
                    .contentType("application/json").content("""
                    {"scope":"AGENT","ownerRef":42,"topic":{"type":"user","title":"语言偏好",
                    "description":"回复时参考","contentMd":"优先中文解释。","idempotencyKey":"http-create"}}
                    """))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(true));
            var store = stores.findActive(7L, "AGENT", 42L);
            assertEquals(2, documents.listLive(7L, store.getId()).size());
            assertTrue(documents.findByPath(7L, store.getId(), "MEMORY.md", false).getContentMd().contains("语言偏好"));
        } finally { com.aliyun.autowonder.context.AutoWonderContext.destroy(); }
    }

    @Test void changedRequestCannotReuseKeyAndUpdateRetryDoesNotOverwriteLaterEdit() {
        var first = service.createForUser(7, 9, true, "AGENT", 42, request("first"));
        assertThrows(MemoryDocumentService.MemoryConflictException.class, () -> service.createForUser(7, 9, true,
                "AGENT", 42, new MemoryTopicRequest(null, "user", "不同内容", "时机", "正文", null, "first")));
        var edit = new MemoryTopicRequest(first.getPath(), "feedback", "新标题", "时机", "第二版", first.getVersion(), "edit");
        var second = service.updateForUser(7, 9, true, first.getStoreId(), edit);
        service.updateForUser(7, 9, true, first.getStoreId(), new MemoryTopicRequest(first.getPath(), "feedback",
                "最终标题", "时机", "第三版", second.getVersion(), "later"));
        service.updateForUser(7, 9, true, first.getStoreId(), edit);
        assertTrue(documents.findByPath(7L, first.getStoreId(), first.getPath(), false).getContentMd().contains("第三版"));
    }
}
