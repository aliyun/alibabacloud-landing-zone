package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.DispatchTransport;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import com.aliyun.autowonder.dispatch.PackageContextAssembler;
import com.aliyun.autowonder.dispatch.SdlcDriver;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.taskpackage.PackageContext;
import com.aliyun.autowonder.taskpackage.TaskPackageResult;
import com.aliyun.autowonder.taskpackage.TaskPackager;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real MySQL 8 rollout gates for both migration and clean-install schema paths. */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduledTaskEndToEndTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    private final ScheduledTaskIntegrationFixture fixture = new ScheduledTaskIntegrationFixture(MYSQL);

    @Test
    void v037MigratesLegacyRowsAndPreservesSourceCompatibility() throws Exception {
        fixture.createDatabase("scheduled_v037");
        try (Connection connection = fixture.open("scheduled_v037")) {
            fixture.createPreV037LegacyTables(connection);
            fixture.applyFile(connection, "docs/migration/V041__scheduled_task.sql");

            assertTrue(fixture.hasColumn(connection, "scheduled_task", "next_fire_at"));
            assertTrue(fixture.hasColumn(connection, "scheduled_task_run", "execution_snapshot_json"));
            for (String table : new String[]{"dispatch", "artifact", "workitem_comment", "workitem_comment_mention", "workitem_comment_delivery"}) {
                assertTrue(fixture.hasColumn(connection, table, "source_type"), table);
                assertEquals(1, fixture.count(connection, "SELECT COUNT(*) FROM " + table + " WHERE source_type = 'WORKITEM'"), table);
            }
            assertTrue(fixture.hasColumn(connection, "dispatch", "normalized_idempotency_key"));
            assertTrue(fixture.hasColumn(connection, "workitem", "origin_type"));
            assertTrue(fixture.hasColumn(connection, "workitem", "origin_id"));
            assertTrue(fixture.hasIndex(connection, "dispatch", "uk_dispatch_normalized_idempotency", "tenant_id", "normalized_idempotency_key"));
            assertTrue(fixture.hasIndex(connection, "dispatch", "idx_dispatch_source", "tenant_id", "source_type", "workitem_id", "id"));
            assertTrue(fixture.hasIndex(connection, "artifact", "idx_artifact_source", "tenant_id", "source_type", "workitem_id", "id"));
            assertTrue(fixture.hasIndex(connection, "workitem_comment", "idx_comment_source", "tenant_id", "source_type", "workitem_id", "id"));
            assertTrue(fixture.hasIndex(connection, "workitem_comment_mention", "idx_mention_source", "tenant_id", "source_type", "workitem_id", "id"));
            assertTrue(fixture.hasIndex(connection, "workitem_comment_delivery", "idx_delivery_source", "tenant_id", "source_type", "workitem_id", "id"));
            assertTrue(fixture.hasIndex(connection, "workitem", "idx_workitem_origin", "tenant_id", "origin_type", "origin_id"));
            assertTrue(fixture.hasIndex(connection, "scheduled_task", "idx_scheduled_task_due", "status", "is_deleted", "next_fire_at", "id"));
            assertTrue(fixture.hasIndex(connection, "scheduled_task", "idx_scheduled_task_owner", "workspace_id", "creator_id", "status", "id"));
            assertTrue(fixture.hasIndex(connection, "scheduled_task_run", "uk_scheduled_task_trigger", "workspace_id", "trigger_key"));
            assertTrue(fixture.hasIndex(connection, "scheduled_task_run", "idx_scheduled_task_run_task", "workspace_id", "scheduled_task_id", "id"));
            assertTrue(fixture.hasIndex(connection, "scheduled_task_run", "idx_scheduled_task_run_status", "workspace_id", "status", "scheduled_at", "id"));
            assertTrue(fixture.hasIndex(connection, "scheduled_task_run", "idx_scheduled_task_run_recovery", "status", "gmt_modified", "id"));
            assertTrue(fixture.hasIndex(connection, "scheduled_task_run", "idx_scheduled_task_run_resume", "workspace_id", "resume_from_run_id"));
            assertTrue(fixture.hasIndex(connection, "scheduled_task_run", "idx_scheduled_task_run_queue", "workspace_id", "scheduled_task_id", "status", "scheduled_at", "id"));
            assertTrue(fixture.hasIndex(connection, "scheduled_task_run", "idx_scheduled_task_run_health", "workspace_id", "scheduled_task_id", "finished_at", "status"));
            assertNormalizedDispatchCollision(connection);
        }
    }

    @Test
    void canonicalSchemaBuildsFreshWithTheSameScheduledTaskContracts() throws Exception {
        fixture.createDatabase("scheduled_fresh");
        try (Connection connection = fixture.open("scheduled_fresh")) {
            fixture.applyFile(connection, "docs/autowonder-schema.sql");
            assertTrue(fixture.hasColumn(connection, "scheduled_task", "instruction_md"));
            assertTrue(fixture.hasColumn(connection, "scheduled_task_run", "trigger_key"));
            assertEquals("WORKITEM", fixture.columnDefault(connection, "dispatch", "source_type"));
            assertEquals("ISOLATED", fixture.columnDefault(connection, "scheduled_task", "session_mode"));
            assertEquals("SKIP", fixture.columnDefault(connection, "scheduled_task", "overlap_policy"));
            assertEquals("FIRE_LATEST", fixture.columnDefault(connection, "scheduled_task", "misfire_policy"));
            assertEquals("ACTIVE", fixture.columnDefault(connection, "scheduled_task", "status"));
            assertEquals("0", fixture.columnDefault(connection, "scheduled_task", "is_deleted"));
            assertEquals("0", fixture.columnDefault(connection, "scheduled_task", "version"));
            assertTrue(fixture.hasIndex(connection, "scheduled_task", "idx_scheduled_task_due", "status", "is_deleted", "next_fire_at", "id"));
            assertTrue(fixture.hasIndex(connection, "scheduled_task", "idx_scheduled_task_owner", "workspace_id", "creator_id", "status", "id"));
            assertTrue(fixture.hasIndex(connection, "scheduled_task_run", "idx_scheduled_task_run_queue", "workspace_id", "scheduled_task_id", "status", "scheduled_at", "id"));
            assertTrue(fixture.hasIndex(connection, "artifact", "idx_artifact_source", "tenant_id", "source_type", "workitem_id", "id"));
            assertTrue(fixture.hasIndex(connection, "workitem", "idx_workitem_origin", "tenant_id", "origin_type", "origin_id"));
            assertTrue(fixture.hasIndex(connection, "dispatch", "uk_dispatch_normalized_idempotency", "tenant_id", "normalized_idempotency_key"));
            assertTrue(fixture.hasIndex(connection, "dispatch", "idx_dispatch_source", "tenant_id", "source_type", "workitem_id", "id"));
            assertTrue(fixture.hasIndex(connection, "workitem_comment", "idx_comment_source", "tenant_id", "source_type", "workitem_id", "id"));
            assertTrue(fixture.hasIndex(connection, "workitem_comment_mention", "idx_mention_source", "tenant_id", "source_type", "workitem_id", "id"));
            assertTrue(fixture.hasIndex(connection, "workitem_comment_delivery", "idx_delivery_source", "tenant_id", "source_type", "workitem_id", "id"));
            assertTrue(fixture.hasIndex(connection, "scheduled_task_run", "idx_scheduled_task_run_resume", "workspace_id", "resume_from_run_id"));
            assertTrue(fixture.hasIndex(connection, "scheduled_task_run", "idx_scheduled_task_run_recovery", "status", "gmt_modified", "id"));
            assertTrue(fixture.hasIndex(connection, "scheduled_task_run", "idx_scheduled_task_run_health", "workspace_id", "scheduled_task_id", "finished_at", "status"));
            assertNormalizedDispatchCollision(connection);
        }
    }

    @Test
    void rawAndNamespacedWorkitemRaceHasOneWinnerAndProductionEnqueueReturnsLegacyWinnerOnBothPaths() throws Exception {
        fixture.createDatabase("scheduled_idempotency_migrated");
        try (Connection connection = fixture.open("scheduled_idempotency_migrated")) {
            fixture.createPreV037LegacyTables(connection);
            fixture.applyFile(connection, "docs/migration/V041__scheduled_task.sql");
            assertLegacyWinnerIsReturnedByProductionEnqueue("scheduled_idempotency_migrated");
        }

        fixture.createDatabase("scheduled_idempotency_fresh");
        try (Connection connection = fixture.open("scheduled_idempotency_fresh")) {
            fixture.applyFile(connection, "docs/autowonder-schema.sql");
        }
        assertLegacyWinnerIsReturnedByProductionEnqueue("scheduled_idempotency_fresh");
    }

    @Test
    void preV037OldWriterThenNewNodeProducesOneRawWorkitemDispatch() throws Exception {
        String database = "scheduled_idempotency_old_then_new";
        fixture.createDatabase(database);
        try (Connection setup = fixture.open(database)) {
            fixture.createPreV037LegacyTables(setup);
        }
        ControlledEnqueue newNode = controlledProductionEnqueue(database, "autowonder-legacy");
        long workspaceId = 41L;
        long workitemId = 83L;
        long stepId = 19L;
        String rawKey = workitemId + ":" + stepId + ":0";

        try (Connection oldNode = fixture.open(database)) {
            oldNode.setAutoCommit(false);
            try (Statement statement = oldNode.createStatement()) {
                statement.executeUpdate("INSERT INTO dispatch(tenant_id, workitem_id, agent_id, sdlc_step_id, idempotency_key) VALUES ("
                        + workspaceId + ", " + workitemId + ", 9, " + stepId + ", '" + rawKey + "')");
                CompletableFuture<DispatchDO> concurrentNewNode = CompletableFuture.supplyAsync(() ->
                        newNode.service().enqueueSubject(workspaceId, ExecutionSourceType.WORKITEM,
                                workitemId, stepId, 9L, 0, 7L));
                assertTrue(newNode.insertAttempted().await(10, TimeUnit.SECONDS),
                        "new compatible node must reach the real MyBatis insert");
                assertFalse(concurrentNewNode.isDone(),
                        "new compatible node must contend on the old writer's raw unique key");
                oldNode.commit();

                assertEquals(rawKey, concurrentNewNode.get(10, TimeUnit.SECONDS).getIdempotencyKey());
                assertTrue(newNode.duplicateObserved().await(10, TimeUnit.SECONDS),
                        "the real duplicate-key recovery path must execute");
                assertEquals(1, queryCount(oldNode,
                        "SELECT COUNT(*) FROM dispatch WHERE tenant_id = " + workspaceId));
                assertEquals(0, normalizedCollisionCount(oldNode));
                assertSingleDeliveryAfterReplay(newNode.dao(), workspaceId, workitemId, stepId, 9L);
                assertEquals(1, queryCount(oldNode,
                        "SELECT COUNT(*) FROM dispatch WHERE tenant_id = " + workspaceId));
            }
        }
    }

    @Test
    void preV037NewNodeThenOldRollbackWriterProducesOneRawWorkitemDispatch() throws Exception {
        String database = "scheduled_idempotency_new_then_old";
        fixture.createDatabase(database);
        try (Connection connection = fixture.open(database)) {
            fixture.createPreV037LegacyTables(connection);
        }
        DispatchService newNode = productionDispatchService(database, "autowonder-legacy");
        long workspaceId = 43L;
        long workitemId = 89L;
        long stepId = 23L;
        String rawKey = workitemId + ":" + stepId + ":0";

        DispatchDO winner = newNode.enqueueSubject(workspaceId, ExecutionSourceType.WORKITEM,
                workitemId, stepId, 9L, 0, 7L);
        assertEquals(rawKey, winner.getIdempotencyKey());
        try (Connection oldNode = fixture.open(database); Statement statement = oldNode.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO dispatch(tenant_id, workitem_id, agent_id, sdlc_step_id, idempotency_key) VALUES ("
                            + workspaceId + ", " + workitemId + ", 9, " + stepId + ", '" + rawKey + "')"));
            assertEquals(1, queryCount(oldNode,
                    "SELECT COUNT(*) FROM dispatch WHERE tenant_id = " + workspaceId));
            assertEquals(0, normalizedCollisionCount(oldNode));
        }
    }

    @Test
    void migratedDatabaseBothFrozenMapperModesRecoverPastNamespacedWinner() throws Exception {
        String database = "scheduled_idempotency_prefixed_winner";
        fixture.createDatabase(database);
        long workspaceId = 47L;
        long workitemId = 97L;
        long stepId = 29L;
        String prefixedKey = "WORKITEM:" + workitemId + ":" + stepId + ":0";
        try (Connection connection = fixture.open(database)) {
            fixture.createPreV037LegacyTables(connection);
            fixture.applyFile(connection, "docs/migration/V041__scheduled_task.sql");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO dispatch(tenant_id, source_type, workitem_id, agent_id, sdlc_step_id, idempotency_key) VALUES ("
                        + workspaceId + ", 'WORKITEM', " + workitemId + ", 9, " + stepId + ", '" + prefixedKey + "')");
            }
        }

        for (String databaseId : new String[]{"autowonder-legacy", "autowonder-source-aware"}) {
            DispatchDO winner = productionDispatchService(database, databaseId).enqueueSubject(
                    workspaceId, ExecutionSourceType.WORKITEM, workitemId, stepId, 9L, 0, 7L);
            assertEquals(prefixedKey, winner.getIdempotencyKey(), databaseId);
        }
        try (Connection connection = fixture.open(database)) {
            assertEquals(1, queryCount(connection,
                    "SELECT COUNT(*) FROM dispatch WHERE tenant_id = " + workspaceId));
        }
    }

    /**
     * The legacy writer owns the normalized unique-key lock before the new node
     * attempts the same historical raw write. They are separate MySQL connections, so
     * committing the legacy connection makes the new-node insert take the real
     * duplicate-key recovery path rather than a mocked exception path.
     */
    private void assertLegacyWinnerIsReturnedByProductionEnqueue(String database) throws Exception {
        ControlledEnqueue newNode = controlledProductionEnqueue(database, "autowonder-source-aware");
        long workspaceId = 31L;
        long workitemId = 73L;
        long stepId = 17L;
        String rawKey = workitemId + ":" + stepId + ":0";
        try (Connection legacyConnection = fixture.open(database)) {
            legacyConnection.setAutoCommit(false);
            try (Statement legacy = legacyConnection.createStatement()) {
                legacy.executeUpdate("INSERT INTO dispatch(tenant_id, workitem_id, agent_id, sdlc_step_id, idempotency_key) VALUES ("
                        + workspaceId + ", " + workitemId + ", 9, " + stepId + ", '" + rawKey + "')");
                CompletableFuture<DispatchDO> concurrentNewNode = CompletableFuture.supplyAsync(() ->
                        newNode.service().enqueueSubject(workspaceId, ExecutionSourceType.WORKITEM,
                                workitemId, stepId, 9L, 0, 7L));
                assertTrue(newNode.insertAttempted().await(10, TimeUnit.SECONDS),
                        "new compatible node must reach the real MyBatis insert");
                assertFalse(concurrentNewNode.isDone(),
                        "raw Workitem insert must wait on the legacy raw-key owner");
                legacyConnection.commit();
                DispatchDO returned = concurrentNewNode.get(10, TimeUnit.SECONDS);
                assertTrue(newNode.duplicateObserved().await(10, TimeUnit.SECONDS),
                        "the real duplicate-key recovery path must execute");
                assertEquals(rawKey, returned.getIdempotencyKey(),
                        "rolling-upgrade recovery must return the legacy-key winner");
                assertEquals(1, queryCount(legacyConnection,
                        "SELECT COUNT(*) FROM dispatch WHERE tenant_id = " + workspaceId));
                assertEquals(1, queryCount(legacyConnection,
                        "SELECT COUNT(*) FROM dispatch WHERE tenant_id = " + workspaceId
                                + " AND idempotency_key = '" + rawKey + "'"));
                assertSingleDeliveryAfterReplay(newNode.dao(), workspaceId, workitemId, stepId, 9L);
                assertEquals(1, queryCount(legacyConnection,
                        "SELECT COUNT(*) FROM dispatch WHERE tenant_id = " + workspaceId));
            }
        }
    }

    private DispatchService productionDispatchService(String database, String databaseId) throws Exception {
        return controlledProductionEnqueue(database, databaseId).service();
    }

    private ControlledEnqueue controlledProductionEnqueue(String database, String databaseId) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + database
                        + "?useUnicode=true&characterEncoding=utf-8&useSSL=false",
                "root", MYSQL.getPassword());
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        Configuration configuration = new Configuration();
        configuration.setDatabaseId(databaseId);
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapping/DispatchDao.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        DispatchDao delegate = new SqlSessionTemplate(factory).getMapper(DispatchDao.class);
        CountDownLatch insertAttempted = new CountDownLatch(1);
        CountDownLatch duplicateObserved = new CountDownLatch(1);
        DispatchDao dao = (DispatchDao) Proxy.newProxyInstance(
                DispatchDao.class.getClassLoader(), new Class<?>[]{DispatchDao.class},
                (proxy, method, args) -> {
                    boolean insert = "insert".equals(method.getName());
                    if (insert) {
                        insertAttempted.countDown();
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        Throwable cause = failure.getCause();
                        if (insert && cause instanceof DuplicateKeyException) {
                            duplicateObserved.countDown();
                        }
                        throw cause;
                    }
                });
        DispatchService service = new DispatchService(dao, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        return new ControlledEnqueue(service, dao, insertAttempted, duplicateObserved);
    }

    private void assertSingleDeliveryAfterReplay(DispatchDao dao, long workspaceId,
                                                  long workitemId, long stepId, long agentId) {
        AgentDO agent = new AgentDO();
        agent.setId(agentId);
        agent.setTenantId(workspaceId);
        agent.setOnlineVersionId(101L);
        AgentVersionDO version = new AgentVersionDO();
        version.setId(101L);
        version.setTenantId(workspaceId);
        version.setAgentId(agentId);

        AgentDao agentDao = mock(AgentDao.class);
        AgentVersionDao versionDao = mock(AgentVersionDao.class);
        ExecutorSelector selector = mock(ExecutorSelector.class);
        PackageContextAssembler assembler = mock(PackageContextAssembler.class);
        TaskPackager packager = mock(TaskPackager.class);
        RedisManager redis = mock(RedisManager.class);
        AtomicInteger deliveries = new AtomicInteger();
        DispatchTransport transport = (dispatch, taskPackage) -> deliveries.incrementAndGet();
        when(agentDao.findById(agentId)).thenReturn(agent);
        when(versionDao.findById(101L)).thenReturn(version);
        when(selector.select(agentId)).thenReturn(501L);
        when(assembler.assemble(any(DispatchDO.class), any(AgentVersionDO.class)))
                .thenReturn(new PackageContext());
        when(packager.build(any(PackageContext.class)))
                .thenReturn(new TaskPackageResult("oss://package", "md5", 1L, "https://download", "sha256"));
        when(redis.tryAcquireLock(any(), any(), anyLong())).thenReturn(true);

        DispatchService driver = new DispatchService(dao, null, null, agentDao, versionDao,
                selector, assembler, packager, transport, mock(SdlcDriver.class), redis,
                null, null, null);
        DispatchDO winner = driver.enqueueSubject(workspaceId, ExecutionSourceType.WORKITEM,
                workitemId, stepId, agentId, 0, 7L);
        assertTrue(driver.runPending(winner.getId()));
        DispatchDO replay = driver.enqueueSubject(workspaceId, ExecutionSourceType.WORKITEM,
                workitemId, stepId, agentId, 0, 7L);
        assertEquals(winner.getId(), replay.getId());
        assertFalse(driver.runPending(replay.getId()),
                "a replay must not drive an already dispatched winner");
        assertEquals(1, deliveries.get(), "one logical assignment must be transported once");
    }

    private record ControlledEnqueue(DispatchService service, DispatchDao dao,
                                     CountDownLatch insertAttempted,
                                     CountDownLatch duplicateObserved) {
    }

    private static void assertNormalizedDispatchCollision(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            boolean fullDispatchContract = connection.getMetaData().getColumns(connection.getCatalog(), null,
                    "dispatch", "agent_id").next();
            String legacyInsert = fullDispatchContract
                    ? "INSERT INTO dispatch(tenant_id, workitem_id, agent_id, idempotency_key) VALUES (17, 42, 1, '42:9:0')"
                    : "INSERT INTO dispatch(tenant_id, workitem_id, idempotency_key) VALUES (17, 42, '42:9:0')";
            String namespacedInsert = fullDispatchContract
                    ? "INSERT INTO dispatch(tenant_id, workitem_id, agent_id, idempotency_key) VALUES (17, 42, 1, 'WORKITEM:42:9:0')"
                    : "INSERT INTO dispatch(tenant_id, workitem_id, idempotency_key) VALUES (17, 42, 'WORKITEM:42:9:0')";
            statement.executeUpdate(legacyInsert);
            SQLException duplicate = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    namespacedInsert));
            assertFalse(duplicate.getMessage().isBlank());
            assertEquals(1, queryCount(connection, "SELECT COUNT(*) FROM dispatch WHERE tenant_id = 17"));
        }
    }

    private static long queryCount(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }


    private static long normalizedCollisionCount(Connection connection) throws SQLException {
        return queryCount(connection, "SELECT COUNT(*) FROM dispatch AS raw_key "
                + "JOIN dispatch AS namespaced ON namespaced.tenant_id = raw_key.tenant_id "
                + "AND namespaced.idempotency_key = CONCAT('WORKITEM:', raw_key.idempotency_key) "
                + "WHERE raw_key.idempotency_key REGEXP '^[0-9]+:[0-9]+:[0-9]+$'");
    }
}
