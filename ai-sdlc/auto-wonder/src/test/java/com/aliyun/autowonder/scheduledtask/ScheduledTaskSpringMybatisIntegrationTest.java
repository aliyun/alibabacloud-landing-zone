package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.audit.AuditLogDao;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchCompensationTask;
import com.aliyun.autowonder.dispatch.DispatchCheckpointDao;
import com.aliyun.autowonder.dispatch.DispatchCheckpointDO;
import com.aliyun.autowonder.dispatch.DispatchCheckpointService;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.DispatchTransport;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import com.aliyun.autowonder.dispatch.PackageContextAssembler;
import com.aliyun.autowonder.dispatch.subject.ExecutionSubjectRegistry;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.artifact.ArtifactService;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.StoredObject;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.artifact.ArtifactOwnerRef;
import com.aliyun.autowonder.artifact.RequirementDocumentService;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.taskpackage.TaskPackager;
import com.aliyun.autowonder.taskpackage.TaskPackageResult;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.squad.SquadMemberDao;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.websocket.NodeIdentity;
import com.aliyun.autowonder.websocket.PresenceManager;
import com.aliyun.autowonder.websocket.ExecutorSession;
import com.aliyun.autowonder.websocket.InboundFrameRouter;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.DockerClientFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Jedis;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.MessageDigest;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These assertions deliberately use production MyBatis XML, production service
 * code and Spring's transaction interceptor. They guard the two enable failure
 * windows that cannot be proven with a mapper mock.
 */
class ScheduledTaskSpringMybatisIntegrationTest {
    private static final String SOURCE_AWARE_DATABASE_ID = "autowonder-source-aware";
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("test").withUsername("test").withPassword("test");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);
    private static ScheduledTaskIntegrationFixture fixture;
    private static ScheduledTaskService service;
    private static ScheduledTaskDao taskDao;
    private static DispatchDao dispatchDao;
    private static AgentDao agentDao;
    private static AgentVersionDao agentVersionDao;
    private static SquadMemberDao squadMemberDao;
    private static ScheduledTaskRunDao runDao;
    private static ScheduledTaskTriggerService triggerService;
    private static TransactionTemplate transaction;
    private static DataSource dataSource;
    private static SqlSessionTemplate sqlSession;
    private static DataSourceTransactionManager transactionManager;
    private static RequirementDocumentService documentsOne;
    private static RequirementDocumentService documentsTwo;
    private static InMemoryStorage documentStorage;
    private static ArtifactDao artifactDao;

    @BeforeAll
    static void setup() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for ScheduledTaskSpringMybatisIntegrationTest");
        MYSQL.start();
        REDIS.start();
        fixture = new ScheduledTaskIntegrationFixture(MYSQL);
        fixture.createDatabase("scheduled_spring");
        try (Connection connection = fixture.open("scheduled_spring")) {
            fixture.applyFile(connection, "docs/autowonder-schema.sql");
            seed(connection);
        }
        dataSource = new DriverManagerDataSource("jdbc:mysql://" + MYSQL.getHost() + ":"
                + MYSQL.getMappedPort(3306) + "/scheduled_spring?useSSL=false", "root", MYSQL.getPassword());
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setTransactionFactory(new SpringManagedTransactionFactory());
        Configuration mybatis = new Configuration();
        mybatis.setDatabaseId(SOURCE_AWARE_DATABASE_ID);
        mybatis.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(mybatis);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapping/*.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        SqlSessionTemplate session = new SqlSessionTemplate(factory);
        sqlSession = session;
        taskDao = session.getMapper(ScheduledTaskDao.class);
        squadMemberDao = session.getMapper(SquadMemberDao.class);
        dispatchDao = session.getMapper(DispatchDao.class);
        runDao = session.getMapper(ScheduledTaskRunDao.class);
        agentDao = session.getMapper(AgentDao.class);
        agentVersionDao = session.getMapper(AgentVersionDao.class);
        ScheduledTaskService target = new ScheduledTaskService(taskDao,
                session.getMapper(SquadDao.class), squadMemberDao, agentDao,
                new AuditLogService(session.getMapper(AuditLogDao.class), null, agentDao),
                new ScheduledTaskSchedule(), Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC));
        transactionManager = new DataSourceTransactionManager(dataSource);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        service = (ScheduledTaskService) proxy.getProxy();
        artifactDao = session.getMapper(ArtifactDao.class);
        triggerService = new ScheduledTaskTriggerService(runDao, artifactDao,
                squadMemberDao, agentDao, agentVersionDao,
                new EmptyObjectStorage());
        triggerService.setTaskDao(taskDao);
        transaction = new TransactionTemplate(transactionManager);
        documentStorage = new InMemoryStorage();
        OssProperties oss = new OssProperties(); oss.setArtifactBucket("test-artifacts");
        AuditLogService audit = new AuditLogService(session.getMapper(AuditLogDao.class), null, agentDao);
        documentsOne = transactionalDocuments(session, audit, oss);
        documentsTwo = transactionalDocuments(session, audit, oss);
    }

    @Test
    void fullV037MapperFactorySelectsSourceAwareStatements() {
        assertEquals(SOURCE_AWARE_DATABASE_ID, sqlSession.getConfiguration().getDatabaseId());
        assertTrue(sqlSession.getConfiguration().hasStatement(
                "com.aliyun.autowonder.artifact.ArtifactDao.findBySourceAndId"));
    }

    @AfterAll
    static void teardown() {
        MYSQL.stop();
        REDIS.stop();
    }

    @Test
    void enableRollsBackWhenSecondPhysicalCasMissesAfterCursorUpdate() throws Exception {
        resetPausedTask();
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER scheduled_enable_cas_miss BEFORE UPDATE ON scheduled_task FOR EACH ROW SET NEW.version = OLD.version + 2");
        }
        assertThrows(BizException.class, () -> service.enable(100L, 0, 1L, 7L));
        assertPausedOriginal();
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER scheduled_enable_cas_miss");
        }
    }

    @Test
    void enableRollsBackWhenRequiredAuditInsertFails() throws Exception {
        resetPausedTask();
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER scheduled_enable_audit_failure BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit gate'");
        }
        assertThrows(RuntimeException.class, () -> service.enable(100L, 0, 1L, 7L));
        assertPausedOriginal();
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER scheduled_enable_audit_failure");
        }
    }

    @Test
    void tenantMappersNeverReturnEqualNumericWorkitemRowsFromAnotherTenant() throws Exception {
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM dispatch WHERE workitem_id=700");
            statement.executeUpdate("INSERT INTO dispatch(tenant_id, source_type, workitem_id, agent_id, idempotency_key) VALUES (1, 'WORKITEM', 700, 40, 'WORKITEM:700:1:0')");
            statement.executeUpdate("INSERT INTO dispatch(tenant_id, source_type, workitem_id, agent_id, idempotency_key) VALUES (2, 'WORKITEM', 700, 40, 'WORKITEM:700:1:0')");
            statement.executeUpdate("INSERT INTO dispatch(tenant_id, source_type, workitem_id, agent_id, idempotency_key) VALUES (1, 'SCHEDULED_TASK_RUN', 700, 40, 'SCHEDULED_TASK_RUN:700:1:0')");
        }
        assertEquals(1, dispatchDao.listByWorkitemIds(1L, java.util.List.of(700L)).size());
        assertEquals(1, dispatchDao.listLatestByWorkitemIds(1L, java.util.List.of(700L)).size());
        assertEquals(1, dispatchDao.listByWorkitemIds(2L, java.util.List.of(700L)).size());
        assertEquals(1, dispatchDao.listBySource(1L, "SCHEDULED_TASK_RUN", 700L).size());
        assertEquals("SCHEDULED_TASK_RUN", dispatchDao.listBySource(1L,
                "SCHEDULED_TASK_RUN", 700L).get(0).getSourceType());
    }

    @Test
    void scheduledTaskListShapesUseTheTenantScopedDaoAndHaveAnExplainPlan() throws Exception {
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            for (int id = 201; id < 241; id++) {
                statement.executeUpdate("INSERT INTO scheduled_task(id, workspace_id, name, instruction_md, squad_id, initial_agent_id, schedule_type, cron_expression, timezone, session_mode, overlap_policy, misfire_policy, start_deadline_seconds, affinity_timeout_seconds, status, next_fire_at, gmt_create, creator_id, is_deleted, version) VALUES ("
                        + id + ", 1, 'list-" + id + "', 'run', 30, 40, 'CRON', '0 0 2 * * *', 'Asia/Shanghai', 'ISOLATED', 'SKIP', 'FIRE_LATEST', 21600, 1800, 'ACTIVE', '2026-08-10 18:00:00.000', '2025-12-31 00:00:00.000', " + (id % 2 == 0 ? 7 : 8) + ", 0, 0)");
            }
            for (String query : new String[]{
                    "SELECT id FROM scheduled_task WHERE workspace_id=1 AND is_deleted=0 ORDER BY id DESC LIMIT 20",
                    "SELECT id FROM scheduled_task WHERE workspace_id=1 AND is_deleted=0 AND status='ACTIVE' ORDER BY id DESC LIMIT 20",
                    "SELECT id FROM scheduled_task WHERE workspace_id=1 AND is_deleted=0 AND creator_id=7 ORDER BY id DESC LIMIT 20",
                    "SELECT id FROM scheduled_task WHERE workspace_id=1 AND is_deleted=0 AND status='ACTIVE' AND creator_id=7 ORDER BY id DESC LIMIT 20"}) {
                try (var result = statement.executeQuery("EXPLAIN " + query)) {
                    assertTrue(result.next(), query);
                    assertTrue(result.getString("key") != null, "expected indexed list shape: " + query);
                }
            }
        }
        assertTrue(taskDao.listByWorkspace(1L, null, null, null, null, 100, 0).stream()
                .allMatch(task -> task.getWorkspaceId() == 1L));
        assertTrue(taskDao.listByWorkspace(1L, "ACTIVE", 7L, null, null, 100, 0).stream()
                .allMatch(task -> "ACTIVE".equals(task.getStatus()) && task.getCreatorId() == 7L));
    }

    @Test
    void claimedCursorRollsBackOnSnapshotFailureThenRetriesWithOneRun() throws Exception {
        resetActiveDueTask();
        seedExecutableAgentAndUnreadableRequirementDocument();
        assertThrows(BizException.class, () -> transaction.executeWithoutResult(status -> {
            ScheduledTaskDO due = taskDao.findById(1L, 100L);
            assertEquals(1, taskDao.claimNextFire(1L, 100L, due.getVersion(), due.getNextFireAt(),
                    java.util.Date.from(Instant.parse("2026-08-11T18:00:00Z")), due.getNextFireAt(), "ACTIVE", 7L));
            triggerService.fireScheduled(due, Instant.parse("2026-08-10T18:00:00Z"), Instant.parse("2026-08-10T18:00:01Z"));
        }));
        ScheduledTaskDO restored = taskDao.findById(1L, 100L);
        assertEquals(0, restored.getVersion());
        assertEquals("ACTIVE", restored.getStatus());
        assertEquals(Instant.parse("2026-08-10T10:00:00Z"), restored.getNextFireAt().toInstant());
        assertEquals(null, restored.getLastFireAt());
        assertEquals(0, runDao.listByTask(1L, 100L, 20, 0).size());

        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM artifact WHERE tenant_id=1 AND source_type='SCHEDULED_TASK' AND workitem_id=100");
        }
        assertNotNull(agentDao.findById(40L));
        assertNotNull(agentVersionDao.findById(41L));
        assertEquals(1L, agentDao.findById(40L).getTenantId());
        assertEquals(1L, agentVersionDao.findById(41L).getTenantId());
        assertEquals(40L, taskDao.findById(1L, 100L).getInitialAgentId());
        assertEquals(1, squadMemberDao.listBySquad(30L).size());
        assertEquals(1L, squadMemberDao.listBySquad(30L).get(0).getTenantId());
        transaction.executeWithoutResult(status -> {
            ScheduledTaskDO due = taskDao.findById(1L, 100L);
            assertEquals(1, taskDao.claimNextFire(1L, 100L, due.getVersion(), due.getNextFireAt(),
                    java.util.Date.from(Instant.parse("2026-08-11T18:00:00Z")), due.getNextFireAt(), "ACTIVE", 7L));
            triggerService.fireScheduled(due, Instant.parse("2026-08-10T18:00:00Z"), Instant.parse("2026-08-10T18:00:01Z"));
        });
        assertEquals(1, runDao.listByTask(1L, 100L, 20, 0).size());
        assertEquals("task:100:scheduled:2026-08-10T18:00:00Z",
                runDao.listByTask(1L, 100L, 20, 0).get(0).getTriggerKey());
    }

    @Test
    void realSchedulerRedisLockRollsBackClaimThenRetriesWithoutDuplicate() throws Exception {
        resetActiveDueTask();
        prepareSingleOccurrenceScannerWindow();
        seedExecutableAgentAndUnreadableRequirementDocument();
        ScheduledTaskDO before = taskDao.findById(1L, 100L);
        ScheduledTaskScheduler scheduler = schedulerAt(scannerNow(before));
        assertThrows(BizException.class, scheduler::scan);
        ScheduledTaskDO restored = taskDao.findById(1L, 100L);
        assertEquals(before.getVersion(), restored.getVersion());
        assertEquals(before.getNextFireAt(), restored.getNextFireAt());
        assertEquals(before.getLastFireAt(), restored.getLastFireAt());
        assertEquals(before.getStatus(), restored.getStatus());
        assertEquals(0, runDao.listByTask(1L, 100L, 20, 0).size());

        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM artifact WHERE tenant_id=1 AND source_type='SCHEDULED_TASK' AND workitem_id=100");
        }
        assertEquals(1, taskDao.findDue(java.util.Date.from(scannerNow(before)), 20).size());
        // Re-run the production scanner at the identical instant. This proves both
        // the Redis owner-token release and the transaction rollback restore the
        // exact due cursor, rather than only proving the trigger in isolation.
        scheduler.scan();
        ScheduledTaskDO advanced = taskDao.findById(1L, 100L);
        assertEquals(before.getVersion() + 1, advanced.getVersion());
        assertEquals("ACTIVE", advanced.getStatus());
        assertTrue(advanced.getNextFireAt().toInstant().isAfter(before.getNextFireAt().toInstant()));
        assertEquals(1, runDao.listByTask(1L, 100L, 20, 0).size());
        scheduler.scan();
        assertEquals(1, runDao.listByTask(1L, 100L, 20, 0).size());
    }

    @Test
    void realSchedulerAdvancesCursorWhenExactScheduledRunAlreadyExists() throws Exception {
        resetActiveDueTask();
        prepareSingleOccurrenceScannerWindow();
        seedExecutableAgent();
        ScheduledTaskDO before = taskDao.findById(1L, 100L);
        transaction.executeWithoutResult(status -> triggerService.fireScheduled(before,
                before.getNextFireAt().toInstant(), before.getNextFireAt().toInstant()));
        assertEquals(1, runDao.listByTask(1L, 100L, 20, 0).size());

        ScheduledTaskScheduler scheduler = schedulerAt(scannerNow(before));
        scheduler.scan();

        ScheduledTaskDO advanced = taskDao.findById(1L, 100L);
        assertEquals(before.getVersion() + 1, advanced.getVersion());
        assertEquals("ACTIVE", advanced.getStatus());
        assertTrue(advanced.getNextFireAt().toInstant().isAfter(before.getNextFireAt().toInstant()));
        assertEquals(1, runDao.listByTask(1L, 100L, 20, 0).size());
        assertEquals(ScheduledTaskTriggerService.scheduledKey(100L, before.getNextFireAt().toInstant()),
                runDao.listByTask(1L, 100L, 20, 0).get(0).getTriggerKey());
    }

    @Test
    void concurrentScheduledTaskDocumentUploadsSerializeOnParentRow() throws Exception {
        resetPausedTask();
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM artifact WHERE tenant_id=1 AND source_type='SCHEDULED_TASK' AND workitem_id=100");
        }
        documentStorage.clear();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> uploadSameDocument(documentsOne, ready, start, "winner"));
            var second = pool.submit(() -> uploadSameDocument(documentsTwo, ready, start, "loser"));
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        } finally { pool.shutdownNow(); }
        try (Connection connection = fixture.open("scheduled_spring")) {
            assertEquals(1, fixture.count(connection, "SELECT COUNT(*) FROM artifact WHERE tenant_id=1 AND source_type='SCHEDULED_TASK' AND workitem_id=100 AND name='requirements/spec.md'"));
        }
        var storedArtifact = artifactDao.listBySource(1L, "SCHEDULED_TASK", 100L,
                RequirementDocumentService.TYPE).get(0);
        assertEquals(1, documentStorage.size());
        assertEquals("test-artifacts/t/1/scheduled-task/100/requirements/spec.md", storedArtifact.getOssRef());
        byte[] winnerBytes = documentStorage.get(storedArtifact.getOssRef());
        assertNotNull(winnerBytes);
        assertEquals(storedArtifact.getSize().longValue(), winnerBytes.length);
        assertTrue(java.util.Set.of("winner", "loser").contains(new String(winnerBytes, java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(java.util.Set.of(sha256("winner".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                sha256("loser".getBytes(java.nio.charset.StandardCharsets.UTF_8))).contains(sha256(winnerBytes)));
    }

    @Test
    void archiveRacingUploadLeavesArchivedTaskImmutableToFurtherDocuments() throws Exception {
        resetPausedTask();
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM artifact WHERE tenant_id=1 AND source_type='SCHEDULED_TASK' AND workitem_id=100");
        }
        documentStorage.clear();
        CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var upload = pool.submit(() -> { ready.countDown(); await(start); try {
                documentsOne.uploadMcp(new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 100L), "race.md", "race".getBytes(), 1L, 7L, "race");
            } catch (BizException ignored) { } });
            var archive = pool.submit(() -> { ready.countDown(); await(start); service.archive(100L, 0, 1L, 7L); });
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)); start.countDown(); upload.get(); archive.get();
        } finally { pool.shutdownNow(); }
        assertEquals("ARCHIVED", taskDao.findById(1L, 100L).getStatus());
        List<com.aliyun.autowonder.artifact.ArtifactDO> documentsAfterArchive = artifactDao.listBySource(1L,
                "SCHEDULED_TASK", 100L, RequirementDocumentService.TYPE);
        long countBefore = documentsAfterArchive.size();
        Map<Long, String> immutableRefs = documentsAfterArchive.stream().collect(java.util.stream.Collectors.toMap(
                com.aliyun.autowonder.artifact.ArtifactDO::getId,
                com.aliyun.autowonder.artifact.ArtifactDO::getOssRef));
        Map<Long, String> immutableHashes = documentsAfterArchive.stream().collect(java.util.stream.Collectors.toMap(
                com.aliyun.autowonder.artifact.ArtifactDO::getId,
                artifact -> sha256(documentStorage.get(artifact.getOssRef()))));
        assertThrows(BizException.class, () -> documentsTwo.uploadMcp(
                new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 100L), "after.md", "after".getBytes(), 1L, 7L, "after"));
        assertEquals(countBefore, documentCount());
        List<com.aliyun.autowonder.artifact.ArtifactDO> afterRejectedMutation = artifactDao.listBySource(1L,
                "SCHEDULED_TASK", 100L, RequirementDocumentService.TYPE);
        assertEquals(immutableRefs, afterRejectedMutation.stream().collect(java.util.stream.Collectors.toMap(
                com.aliyun.autowonder.artifact.ArtifactDO::getId,
                com.aliyun.autowonder.artifact.ArtifactDO::getOssRef)));
        assertEquals(immutableHashes, afterRejectedMutation.stream().collect(java.util.stream.Collectors.toMap(
                com.aliyun.autowonder.artifact.ArtifactDO::getId,
                artifact -> sha256(documentStorage.get(artifact.getOssRef())))));
    }

    @Test
    void archiveRacingDeleteCannotMutateDocumentsAfterArchiveCommits() throws Exception {
        resetPausedTask(); documentStorage.clear();
        documentsOne.uploadMcp(new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 100L), "delete.md", "delete".getBytes(), 1L, 7L, "seed");
        long artifactId = artifactDao.listBySource(1L, "SCHEDULED_TASK", 100L, "REQUIREMENT_DOC").get(0).getId();
        CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var delete = pool.submit(() -> { ready.countDown(); await(start); try {
                documentsTwo.delete(new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 100L), artifactId, 1L, 7L);
            } catch (BizException ignored) { } });
            var archive = pool.submit(() -> { ready.countDown(); await(start); service.archive(100L, 0, 1L, 7L); });
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)); start.countDown(); delete.get(); archive.get();
        } finally { pool.shutdownNow(); }
        assertEquals("ARCHIVED", taskDao.findById(1L, 100L).getStatus());
        long countAfterArchive = documentCount();
        assertThrows(BizException.class, () -> documentsOne.delete(new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 100L), artifactId, 1L, 7L));
        assertEquals(countAfterArchive, documentCount());
    }

    private static long documentCount() throws Exception {
        try (Connection connection = fixture.open("scheduled_spring")) {
            return fixture.count(connection, "SELECT COUNT(*) FROM artifact WHERE tenant_id=1 AND source_type='SCHEDULED_TASK' AND workitem_id=100");
        }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
    }

    @Test
    void persistedSkipAndContinuousPoliciesCreateAuditableOverlapRuns() throws Exception {
        for (String mode : new String[]{"ISOLATED", "CONTINUOUS"}) {
            resetActiveDueTask();
            try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO agent_version(id, tenant_id, agent_id, version_no, status, role_name, role_code, is_deleted, version) VALUES (41, 1, 40, 1, 'APPROVED', 'worker', 'ops', 0, 0)");
                statement.executeUpdate("UPDATE scheduled_task SET session_mode='" + mode + "', overlap_policy='SKIP' WHERE id=100");
                statement.executeUpdate("INSERT INTO scheduled_task_run(workspace_id, scheduled_task_id, trigger_key, trigger_type, scheduled_at, status, squad_id, initial_agent_id, session_mode, execution_snapshot_json, owner_id, creator_id) VALUES (1,100,'active-" + mode + "','MANUAL','2026-08-10 18:00:00.000','RUNNING',30,40,'" + mode + "',JSON_OBJECT(),7,7)");
            }
            transaction.executeWithoutResult(status -> triggerService.fireScheduled(taskDao.findById(1L, 100L),
                    Instant.parse("2026-08-10T10:01:00Z"), Instant.parse("2026-08-10T10:01:01Z")));
            var runs = runDao.listByTask(1L, 100L, 20, 0);
            assertEquals(2, runs.size());
            assertTrue(runs.stream().anyMatch(run -> "SKIPPED".equals(run.getStatus()) && "OVERLAP".equals(run.getSkipReason())));
        }
    }

    /**
     * This is deliberately not a mapper-level race.  Each contender has a separate
     * DataSourceTransactionManager and SqlSessionFactory, so MySQL's parent-row
     * lock is the only serialization mechanism shared by the two scheduler nodes.
     */
    @Test
    void independentSpringTransactionsSerializeSkipAndContinuousOverlapDecisions() throws Exception {
        for (String mode : new String[]{"ISOLATED", "CONTINUOUS"}) {
            resetActiveDueTask();
            try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO agent_version(id, tenant_id, agent_id, version_no, status, role_name, role_code, is_deleted, version) VALUES (41, 1, 40, 1, 'APPROVED', 'worker', 'ops', 0, 0)");
                statement.executeUpdate("UPDATE scheduled_task SET session_mode='" + mode
                        + "', overlap_policy='SKIP' WHERE workspace_id=1 AND id=100");
            }
            TriggerNode firstNode = independentTriggerNode();
            TriggerNode secondNode = independentTriggerNode();
            CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> fireAfterBarrier(firstNode, ready, start,
                        Instant.parse("2026-08-10T10:01:00Z")));
                var second = pool.submit(() -> fireAfterBarrier(secondNode, ready, start,
                        Instant.parse("2026-08-10T10:02:00Z")));
                assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
                start.countDown(); first.get(); second.get();
            } finally { pool.shutdownNow(); }

            var runs = runDao.listByTask(1L, 100L, 20, 0);
            assertEquals(2, runs.size(), mode);
            assertEquals(1, runs.stream().filter(run -> "QUEUED".equals(run.getStatus())).count(), mode);
            assertEquals(1, runs.stream().filter(run -> "SKIPPED".equals(run.getStatus())
                    && "OVERLAP".equals(run.getSkipReason())).count(), mode);
            assertEquals(1, runDao.findActiveByTask(1L, 100L).size(), mode);
        }
    }

    @Test
    void schedulerPersistsFireLatestFireAllAndSkipAllMisfireReasons() throws Exception {
        for (String policy : new String[]{"FIRE_LATEST", "FIRE_ALL", "SKIP_ALL"}) {
            resetActiveDueTask();
            try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO agent_version(id, tenant_id, agent_id, version_no, status, role_name, role_code, is_deleted, version) VALUES (41, 1, 40, 1, 'APPROVED', 'worker', 'ops', 0, 0)");
                // Four minute-spaced due occurrences: the first two are expired,
                // while the latter two exercise each persisted misfire policy.
                statement.executeUpdate("UPDATE scheduled_task SET cron_expression='0 * * * * *', timezone='UTC',"
                        + " misfire_policy='" + policy + "', start_deadline_seconds=90,"
                        + " overlap_policy='QUEUE', session_mode='ISOLATED', status='ACTIVE',"
                        + " next_fire_at='2026-08-10 10:00:00.000', last_fire_at=NULL, version=0"
                        + " WHERE workspace_id=1 AND id=100");
            }
            // JDBC DATETIME conversion is JVM-zone dependent in this build.  Derive the
            // scanner instant from the persisted cursor so this remains a four-occurrence
            // production scan on every supported developer/CI timezone.
            Instant firstDue = taskDao.findById(1L, 100L).getNextFireAt().toInstant();
            ScheduledTaskScheduler scheduler = schedulerAt(firstDue.plusSeconds(180));
            scheduler.scan();
            var runs = runDao.listByTask(1L, 100L, 20, 0);
            assertEquals(4, runs.size(), policy);
            assertEquals(2, runs.stream().filter(run -> "SKIPPED".equals(run.getStatus())
                    && "START_DEADLINE".equals(run.getSkipReason())).count(), policy);
            if ("FIRE_LATEST".equals(policy)) {
                assertEquals(1, runs.stream().filter(run -> "QUEUED".equals(run.getStatus())).count());
                assertEquals(1, runs.stream().filter(run -> "SKIPPED".equals(run.getStatus())
                        && "MISFIRE_POLICY".equals(run.getSkipReason())).count());
            } else if ("FIRE_ALL".equals(policy)) {
                assertEquals(2, runs.stream().filter(run -> "QUEUED".equals(run.getStatus())).count());
                assertEquals(0, runs.stream().filter(run -> "MISFIRE_POLICY".equals(run.getSkipReason())).count());
            } else {
                assertEquals(0, runs.stream().filter(run -> "QUEUED".equals(run.getStatus())).count());
                assertEquals(2, runs.stream().filter(run -> "SKIPPED".equals(run.getStatus())
                        && "MISFIRE_POLICY".equals(run.getSkipReason())).count());
            }
        }
    }

    @Test
    void persistedMisfireReasonsAndDuplicateTriggerRecoveryRemainDistinct() throws Exception {
        resetActiveDueTask();
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO agent_version(id, tenant_id, agent_id, version_no, status, role_name, role_code, is_deleted, version) VALUES (41, 1, 40, 1, 'APPROVED', 'worker', 'ops', 0, 0)");
        }
        transaction.executeWithoutResult(status -> {
            ScheduledTaskDO task = taskDao.findById(1L, 100L);
            triggerService.fireMisfire(task, Instant.parse("2026-08-10T09:00:00Z"), Instant.parse("2026-08-10T10:00:00Z"), "START_DEADLINE");
            triggerService.fireMisfire(task, Instant.parse("2026-08-10T09:01:00Z"), Instant.parse("2026-08-10T10:00:00Z"), "MISFIRE_POLICY");
        });
        var skipped = runDao.listByTask(1L, 100L, 20, 0);
        assertTrue(skipped.stream().anyMatch(run -> "START_DEADLINE".equals(run.getSkipReason())));
        assertTrue(skipped.stream().anyMatch(run -> "MISFIRE_POLICY".equals(run.getSkipReason())));

        resetActiveDueTask();
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO agent_version(id, tenant_id, agent_id, version_no, status, role_name, role_code, is_deleted, version) VALUES (41, 1, 40, 1, 'APPROVED', 'worker', 'ops', 0, 0)");
        }
        Instant occurrence = Instant.parse("2026-08-10T10:00:00Z");
        transaction.executeWithoutResult(status -> {
            ScheduledTaskDO task = taskDao.findById(1L, 100L);
            assertEquals(triggerService.fireScheduled(task, occurrence, occurrence).getId(),
                    triggerService.fireScheduled(task, occurrence, occurrence).getId());
        });
        assertEquals(1, runDao.listByTask(1L, 100L, 20, 0).size());
    }

    @Test
    void noSdlcRunUsesTheProductionDispatchPathThroughAckAndResult() throws Exception {
        resetActiveDueTask();
        seedExecutableAgentAndUnreadableRequirementDocument();
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM artifact WHERE tenant_id=1 AND source_type='SCHEDULED_TASK' AND workitem_id=100");
            // runPending drains by agent after a terminal result. Keep the fixture source-pure:
            // a historical WORKITEM PENDING row must not be accidentally assembled by this
            // scheduled-run-only registry.
            statement.executeUpdate("DELETE FROM dispatch WHERE tenant_id=1");
            statement.executeUpdate("INSERT INTO sdlc(id, tenant_id, name, status, is_deleted, version) VALUES (900, 1, 'review-flow', 'ENABLED', 0, 0)");
            statement.executeUpdate("INSERT INTO sdlc_step(id, tenant_id, sdlc_id, step_order, name, kind, required, is_deleted) VALUES (901, 1, 900, 1, 'review', 'review', 1, 0)");
            statement.executeUpdate("INSERT INTO agent(id, tenant_id, name, status, online_version_id, is_deleted, version) VALUES (41, 1, 'reviewer', 'ONLINE', 42, 0, 0)");
            statement.executeUpdate("INSERT INTO agent_version(id, tenant_id, agent_id, version_no, status, role_name, role_code, sdlc_id, is_deleted, version) VALUES (42, 1, 41, 1, 'APPROVED', 'reviewer', 'review', 900, 0, 0)");
            statement.executeUpdate("INSERT INTO squad_member(tenant_id, squad_id, agent_id) VALUES (1, 30, 41)");
        }

        RedisManager redis = new RedisManager(new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379)), false);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        PresenceManager presence = new PresenceManager(redis, new NodeIdentity());
        presence.register(501L, 40L, 2);
        presence.register(502L, 41L, 2);
        ExecutorSelector selector = new ExecutorSelector(redis, registry, presence, dispatchDao);
        InMemoryObjectStorage packages = new InMemoryObjectStorage();
        RecordingTransport transport = new RecordingTransport();
        PackageContextAssembler assembler = new PackageContextAssembler(new ExecutionSubjectRegistry(java.util.List.of(
                new ScheduledRunExecutionSubjectProvider(runDao, artifactDao))));
        DispatchService dispatchService = new DispatchService(dispatchDao,
                sessionMapper(DispatchRuntimeEventDao.class),
                sessionMapper(WorkitemDao.class), agentDao, agentVersionDao, selector, assembler,
                new TaskPackager(packages, "task-packages", "http://localhost"), transport, null,
                redis, null, new AuditLogService(sessionMapper(AuditLogDao.class), null, agentDao), registry);
        ScheduledTaskRunService runService = new ScheduledTaskRunService(runDao);
        dispatchService.setScheduledTaskRunService(runService);
        ScheduledTaskRunOrchestrator orchestrator = new ScheduledTaskRunOrchestrator(runDao, dispatchService);
        orchestrator.setDocumentDependencies(artifactDao, new EmptyObjectStorage());
        orchestrator.setRunService(runService);
        dispatchService.setScheduledTaskRunOrchestrator(orchestrator);
        triggerService.setRunOrchestrator(orchestrator);
        triggerService.setSdlcStepDao(sessionMapper(SdlcStepDao.class));
        ScheduledTaskRunCommentService runComments = new ScheduledTaskRunCommentService(runDao,
                sessionMapper(WorkitemCommentDao.class));
        ArtifactService artifacts = new ArtifactService(artifactDao, packages);
        // ARTIFACT_UPLOADED is the executor's production WebSocket callback path.
        // This fixture deliberately has no mocked collaborators: the artifact branch
        // only depends on Dispatch ownership and ArtifactService persistence.
        InboundFrameRouter inbound = new InboundFrameRouter(dispatchService, artifacts,
                null, null, null, null, null, null, null, null, null, null,
                availableCapabilityGuard());

        // This is intentionally the same immutable snapshot and start path that a real trigger
        // uses. No bound SDLC means the package must omit sdlc.json rather than follow workitem flow.
        ScheduledTaskRunDO run = triggerService.fireManual(taskDao.findById(1L, 100L), "no-sdlc-e2e");
        assertEquals(null, run.getSdlcId());
        assertEquals(null, run.getCurrentStepId());
        assertEquals(1, transport.count);
        DispatchDO persisted = dispatchDao.listBySource(1L, "SCHEDULED_TASK_RUN", run.getId()).get(0);
        assertEquals("SCHEDULED_TASK_RUN", persisted.getSourceType());
        assertEquals("DISPATCHED", persisted.getStatus());
        assertNotNull(persisted.getPackageOssRef());
        assertTrue(packages.exists(persisted.getPackageOssRef()));
        runComments.addAgentComment(1L, run.getId(), 40L, "agent A handoff evidence");
        inbound.route(new ExecutorSession(501L, 40L, 1L, null), artifactFrame(
                persisted.getId(), 999_999L, "agent-a-report.md", "oss://run/a", 11L));

        dispatchService.onAck(1L, persisted.getId());
        dispatchService.onProgress(1L, persisted.getId());
        assertEquals("RUNNING", dispatchDao.findById(persisted.getId()).getStatus());
        assertEquals("RUNNING", runDao.findById(1L, run.getId()).getStatus());
        assertTrue(dispatchService.onResult(1L, 501L, persisted.getId(), true, "handoff", null, false, true));
        assertEquals("SUCCEEDED", dispatchDao.findById(persisted.getId()).getStatus());
        var handoff = orchestrator.handoff(dispatchDao.findById(persisted.getId()), 41L);
        assertEquals(com.aliyun.autowonder.dispatch.HandoffResult.Status.AGENT_DISPATCHED, handoff.status(), handoff.toString());
        DispatchDO downstream = dispatchDao.findById(handoff.downstreamDispatchId());
        assertEquals(41L, downstream.getAgentId());
        assertEquals(42L, downstream.getAgentVersionId());
        assertEquals("SCHEDULED_TASK_RUN", downstream.getSourceType());
        assertEquals(2, transport.count);
        runComments.addAgentComment(1L, run.getId(), 41L, "agent B completion evidence");
        inbound.route(new ExecutorSession(502L, 41L, 1L, null), artifactFrame(
                downstream.getId(), 888_888L, "agent-b-report.md", "oss://run/b", 12L));
        dispatchService.onAck(1L, downstream.getId());
        dispatchService.onProgress(1L, downstream.getId());
        assertTrue(dispatchService.onResult(1L, 502L, downstream.getId(), true, "completed", null));
        assertEquals("SUCCEEDED", runDao.findById(1L, run.getId()).getStatus());
        assertEquals(2, dispatchDao.listBySource(1L, "SCHEDULED_TASK_RUN", run.getId()).size());
        var persistedComments = sessionMapper(WorkitemCommentDao.class).listBySource(1L,
                "SCHEDULED_TASK_RUN", run.getId());
        assertEquals(2, persistedComments.size());
        assertTrue(persistedComments.stream().map(WorkitemCommentDO::getAuthorRef)
                .collect(java.util.stream.Collectors.toSet()).containsAll(java.util.Set.of(40L, 41L)));
        var persistedArtifacts = artifactDao.listBySource(1L, "SCHEDULED_TASK_RUN", run.getId(), null);
        assertEquals(2, persistedArtifacts.size());
        assertTrue(persistedArtifacts.stream().map(com.aliyun.autowonder.artifact.ArtifactDO::getName)
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(java.util.Set.of("agent-a-report.md", "agent-b-report.md")));
        assertEquals(0, artifactDao.listBySource(1L, "WORKITEM", run.getId(), null).size());
        triggerService.setRunOrchestrator(null);
    }

    @Test
    void initialRunDeliveryWaitsForOuterMysqlCommitAndThenAcceptsInboundArtifact() throws Exception {
        resetActiveDueTask();
        seedExecutableAgent();
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM artifact WHERE tenant_id=1 AND source_type='SCHEDULED_TASK' AND workitem_id=100");
            statement.executeUpdate("DELETE FROM dispatch WHERE tenant_id=1");
        }
        RedisManager redis = new RedisManager(new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379)), false);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        PresenceManager presence = new PresenceManager(redis, new NodeIdentity());
        presence.register(504L, 40L, 2);
        RecordingTransport transport = new RecordingTransport();
        DispatchService dispatchService = new DispatchService(dispatchDao, sessionMapper(DispatchRuntimeEventDao.class),
                sessionMapper(WorkitemDao.class), agentDao, agentVersionDao,
                new ExecutorSelector(redis, registry, presence, dispatchDao),
                new PackageContextAssembler(new ExecutionSubjectRegistry(java.util.List.of(
                        new ScheduledRunExecutionSubjectProvider(runDao, artifactDao)))),
                new TaskPackager(new InMemoryObjectStorage(), "task-packages", "http://localhost"), transport,
                null, redis, null, new AuditLogService(sessionMapper(AuditLogDao.class), null, agentDao), registry);
        ScheduledTaskRunService runService = new ScheduledTaskRunService(runDao);
        dispatchService.setScheduledTaskRunService(runService);
        ScheduledTaskRunOrchestrator target = new ScheduledTaskRunOrchestrator(runDao, dispatchService);
        target.setDocumentDependencies(artifactDao, new EmptyObjectStorage());
        target.setRunService(runService);
        ProxyFactory orchestratorProxy = new ProxyFactory(target);
        orchestratorProxy.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        ScheduledTaskRunOrchestrator orchestrator = (ScheduledTaskRunOrchestrator) orchestratorProxy.getProxy();
        dispatchService.setScheduledTaskRunOrchestrator(orchestrator);
        triggerService.setRunOrchestrator(orchestrator);
        AtomicReference<ScheduledTaskRunDO> created = new AtomicReference<>();
        try {
            transaction.executeWithoutResult(status -> {
                ScheduledTaskRunDO run = triggerService.fireManual(taskDao.findById(1L, 100L), "after-commit-e2e");
                created.set(run);
                assertEquals(0, transport.count, "transport must not see an uncommitted Run");
                assertEquals(0, dispatchDao.listBySource(1L, "SCHEDULED_TASK_RUN", run.getId()).size());
            });
            ScheduledTaskRunDO run = created.get();
            assertNotNull(run);
            assertEquals(1, transport.count);
            DispatchDO dispatch = dispatchDao.listBySource(1L, "SCHEDULED_TASK_RUN", run.getId()).get(0);
            InboundFrameRouter inbound = new InboundFrameRouter(dispatchService,
                    new ArtifactService(artifactDao, new InMemoryObjectStorage()), null, null, null, null, null,
                    null, null, null, null, null, availableCapabilityGuard());
            inbound.route(new ExecutorSession(504L, 40L, 1L, null), artifactFrame(
                    dispatch.getId(), 999_998L, "after-commit.md", "oss://run/after-commit", 13L));
            assertEquals(1, artifactDao.listBySource(1L, "SCHEDULED_TASK_RUN", run.getId(), "REPORT").size());
        } finally {
            triggerService.setRunOrchestrator(null);
        }
    }

    @Test
    void initialRunDeliveryIsDiscardedWhenOuterMysqlTransactionRollsBack() throws Exception {
        resetActiveDueTask();
        seedExecutableAgent();
        RedisManager redis = new RedisManager(new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379)), false);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        PresenceManager presence = new PresenceManager(redis, new NodeIdentity());
        presence.register(505L, 40L, 2);
        RecordingTransport transport = new RecordingTransport();
        DispatchService dispatchService = new DispatchService(dispatchDao, sessionMapper(DispatchRuntimeEventDao.class),
                sessionMapper(WorkitemDao.class), agentDao, agentVersionDao,
                new ExecutorSelector(redis, registry, presence, dispatchDao),
                new PackageContextAssembler(new ExecutionSubjectRegistry(java.util.List.of(
                        new ScheduledRunExecutionSubjectProvider(runDao, artifactDao)))),
                new TaskPackager(new InMemoryObjectStorage(), "task-packages", "http://localhost"), transport,
                null, redis, null, new AuditLogService(sessionMapper(AuditLogDao.class), null, agentDao), registry);
        ScheduledTaskRunService runService = new ScheduledTaskRunService(runDao);
        dispatchService.setScheduledTaskRunService(runService);
        ScheduledTaskRunOrchestrator target = new ScheduledTaskRunOrchestrator(runDao, dispatchService);
        target.setDocumentDependencies(artifactDao, new EmptyObjectStorage()); target.setRunService(runService);
        ProxyFactory orchestratorProxy = new ProxyFactory(target);
        orchestratorProxy.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        ScheduledTaskRunOrchestrator orchestrator = (ScheduledTaskRunOrchestrator) orchestratorProxy.getProxy();
        dispatchService.setScheduledTaskRunOrchestrator(orchestrator);
        triggerService.setRunOrchestrator(orchestrator);
        try {
            transaction.executeWithoutResult(status -> {
                triggerService.fireManual(taskDao.findById(1L, 100L), "after-rollback-e2e");
                assertEquals(0, transport.count);
                status.setRollbackOnly();
            });
            assertEquals(0, transport.count);
            assertEquals(0, runDao.listByTask(1L, 100L, 20, 0).size());
        } finally {
            triggerService.setRunOrchestrator(null);
        }
    }

    private static String artifactFrame(long dispatchId, long legacyWorkitemId, String name,
                                        String ossRef, long size) {
        return "{\"type\":\"ARTIFACT_UPLOADED\",\"dispatchId\":" + dispatchId
                + ",\"workitemId\":" + legacyWorkitemId + ",\"name\":\"" + name
                + "\",\"artifactType\":\"REPORT\",\"ossRef\":\"" + ossRef
                + "\",\"size\":" + size + "}";
    }

    @Test
    void runCompensationRecoversClaimedQueuedRunWithoutDuplicatingRootDispatch() throws Exception {
        resetActiveDueTask();
        seedExecutableAgentAndUnreadableRequirementDocument();
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM artifact WHERE tenant_id=1 AND source_type='SCHEDULED_TASK' AND workitem_id=100");
            statement.executeUpdate("DELETE FROM dispatch WHERE tenant_id=1");
        }
        triggerService.setRunOrchestrator(null);
        ScheduledTaskRunDO run = triggerService.fireManual(taskDao.findById(1L, 100L), "crash-after-claim");
        assertEquals("QUEUED", run.getStatus());
        assertEquals(0, dispatchDao.listBySource(1L, "SCHEDULED_TASK_RUN", run.getId()).size());
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE scheduled_task_run SET gmt_modified=DATE_SUB(NOW(3), INTERVAL 2 MINUTE) WHERE id=" + run.getId());
        }

        RedisManager redis = new RedisManager(new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379)), false);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        PresenceManager presence = new PresenceManager(redis, new NodeIdentity()); presence.register(503L, 40L, 2);
        ExecutorSelector selector = new ExecutorSelector(redis, registry, presence, dispatchDao);
        RecordingTransport transport = new RecordingTransport();
        DispatchService dispatchService = new DispatchService(dispatchDao, sessionMapper(DispatchRuntimeEventDao.class),
                sessionMapper(WorkitemDao.class), agentDao, agentVersionDao, selector,
                new PackageContextAssembler(new ExecutionSubjectRegistry(java.util.List.of(
                        new ScheduledRunExecutionSubjectProvider(runDao, artifactDao)))),
                new TaskPackager(new InMemoryObjectStorage(), "task-packages", "http://localhost"), transport,
                null, redis, null, new AuditLogService(sessionMapper(AuditLogDao.class), null, agentDao), registry);
        ScheduledTaskRunService runService = new ScheduledTaskRunService(runDao);
        dispatchService.setScheduledTaskRunService(runService);
        ScheduledTaskRunOrchestrator orchestrator = new ScheduledTaskRunOrchestrator(runDao, dispatchService);
        orchestrator.setDocumentDependencies(artifactDao, new EmptyObjectStorage()); orchestrator.setRunService(runService);
        dispatchService.setScheduledTaskRunOrchestrator(orchestrator);
        ScheduledTaskRunRecoveryService recovery = new ScheduledTaskRunRecoveryService(runDao, dispatchDao, selector, dispatchService);
        recovery.setRunService(runService);
        ScheduledTaskRunCompensationTask compensation = new ScheduledTaskRunCompensationTask(runDao, orchestrator,
                recovery, redis, runService, availableCapabilityGuard());
        compensation.sweep(); compensation.sweep();
        assertEquals(1, dispatchDao.listBySource(1L, "SCHEDULED_TASK_RUN", run.getId()).size());
        assertEquals(1, transport.count);
        ScheduledTaskRunDO recovered = runDao.findById(1L, run.getId());
        assertEquals("WAITING_EXECUTOR", recovered.getStatus(), recovered.getError());
    }

    @Test
    void recoveredStartingRunAcceptsItsEqualFrozenPinAndDeliversExactlyOnce() throws Exception {
        resetActiveDueTask();
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO agent_version(id, tenant_id, agent_id, version_no, status, role_name, role_code, is_deleted, version) VALUES (41, 1, 40, 1, 'APPROVED', 'worker', 'ops', 0, 0)");
            statement.executeUpdate("DELETE FROM dispatch WHERE tenant_id=1");
        }
        ScheduledTaskRunDO run = continuousRun("equal-pinned-crash-recovery", "STARTING",
                Instant.parse("2026-08-10T00:00:00Z"));
        runDao.insert(run);
        DispatchDO existing = insertDispatch(run.getId(),
                "SCHEDULED_TASK_RUN:" + run.getId() + ":root:1", null, null, "PENDING");
        RecordingTransport transport = new RecordingTransport();
        ScheduledTaskRunOrchestrator orchestrator = recoveryOrchestrator(transport);

        // A process can die after persisting the snapshot pin and before driving its
        // root dispatch. Recovery must accept that exact immutable pin, not issue a
        // second row or send a duplicate task frame.
        orchestrator.start(1L, run.getId(), 7L);
        orchestrator.start(1L, run.getId(), 7L);

        assertEquals(1, dispatchDao.listBySource(1L, "SCHEDULED_TASK_RUN", run.getId()).size());
        assertEquals(1, transport.count);
        assertEquals(41L, dispatchDao.findById(existing.getId()).getAgentVersionId());
        assertEquals("WAITING_EXECUTOR", runDao.findById(1L, run.getId()).getStatus());
    }

    @Test
    void recoveredStartingRunRejectsDifferentFrozenPinWithoutMutatingDispatch() throws Exception {
        resetActiveDueTask();
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO agent_version(id, tenant_id, agent_id, version_no, status, role_name, role_code, is_deleted, version) VALUES (41, 1, 40, 1, 'APPROVED', 'worker', 'ops', 0, 0)");
            statement.executeUpdate("DELETE FROM dispatch WHERE tenant_id=1");
        }
        ScheduledTaskRunDO run = continuousRun("different-pinned-crash-recovery", "STARTING",
                Instant.parse("2026-08-10T00:00:00Z"));
        runDao.insert(run);
        DispatchDO existing = insertDispatch(run.getId(),
                "SCHEDULED_TASK_RUN:" + run.getId() + ":root:1", null, null, "PENDING");
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE dispatch SET agent_version_id=42 WHERE id=" + existing.getId());
        }
        RecordingTransport transport = new RecordingTransport();
        ScheduledTaskRunOrchestrator orchestrator = recoveryOrchestrator(transport);

        orchestrator.start(1L, run.getId(), 7L);

        DispatchDO unchanged = dispatchDao.findById(existing.getId());
        assertEquals(42L, unchanged.getAgentVersionId());
        assertEquals("PENDING", unchanged.getStatus());
        assertEquals(0, transport.count);
        ScheduledTaskRunDO failed = runDao.findById(1L, run.getId());
        assertEquals("FAILED", failed.getStatus());
        assertTrue(failed.getError().contains("30005"), failed.getError());
    }

    @Test
    void twoScannerNodesClaimOneOccurrenceAndProduceOneRootDispatch() throws Exception {
        resetActiveDueTask();
        seedExecutableAgentAndUnreadableRequirementDocument();
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM artifact WHERE tenant_id=1 AND source_type='SCHEDULED_TASK' AND workitem_id=100");
            statement.executeUpdate("DELETE FROM dispatch WHERE tenant_id=1");
        }
        RedisManager redis = new RedisManager(new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379)), false);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        new PresenceManager(redis, new NodeIdentity()).register(502L, 40L, 2);
        RecordingTransport transport = new RecordingTransport();
        DispatchService dispatchService = new DispatchService(dispatchDao,
                sessionMapper(DispatchRuntimeEventDao.class), sessionMapper(WorkitemDao.class), agentDao,
                agentVersionDao, new ExecutorSelector(redis, registry, new PresenceManager(redis, new NodeIdentity()), dispatchDao),
                new PackageContextAssembler(new ExecutionSubjectRegistry(java.util.List.of(
                        new ScheduledRunExecutionSubjectProvider(runDao, artifactDao)))),
                new TaskPackager(new InMemoryObjectStorage(), "task-packages", "http://localhost"), transport,
                null, redis, null, new AuditLogService(sessionMapper(AuditLogDao.class), null, agentDao), registry);
        ScheduledTaskRunService runService = new ScheduledTaskRunService(runDao);
        dispatchService.setScheduledTaskRunService(runService);
        ScheduledTaskRunOrchestrator orchestrator = new ScheduledTaskRunOrchestrator(runDao, dispatchService);
        orchestrator.setDocumentDependencies(artifactDao, new EmptyObjectStorage());
        orchestrator.setRunService(runService);
        dispatchService.setScheduledTaskRunOrchestrator(orchestrator);
        triggerService.setRunOrchestrator(orchestrator);
        try {
            Instant scanAt = taskDao.findById(1L, 100L).getNextFireAt().toInstant().plusSeconds(1);
            ScheduledTaskScheduler nodeOne = schedulerAt(scanAt);
            ScheduledTaskScheduler nodeTwo = schedulerAt(scanAt);
            CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> { ready.countDown(); await(start); nodeOne.scan(); });
                var second = pool.submit(() -> { ready.countDown(); await(start); nodeTwo.scan(); });
                assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
                start.countDown(); first.get(); second.get();
            } finally { pool.shutdownNow(); }
            assertEquals(1, runDao.listByTask(1L, 100L, 20, 0).size());
            ScheduledTaskRunDO run = runDao.listByTask(1L, 100L, 20, 0).get(0);
            assertEquals("WAITING_EXECUTOR", run.getStatus());
            assertEquals(1, dispatchDao.listBySource(1L, "SCHEDULED_TASK_RUN", run.getId()).size());
            assertEquals(1, transport.count);

            // Exercise the durable recovery twice. It may redeliver after a lost ACK, but the
            // source key remains one row; a second compensator can never create a second root.
            DispatchDO root = dispatchDao.listBySource(1L, "SCHEDULED_TASK_RUN", run.getId()).get(0);
            try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE dispatch SET gmt_modified=DATE_SUB(NOW(3), INTERVAL 3 MINUTE) WHERE id=" + root.getId());
            }
            DispatchCompensationTask compensation = new DispatchCompensationTask(dispatchDao, dispatchService,
                    null, null, redis);
            compensation.sweep();
            try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE dispatch SET gmt_modified=DATE_SUB(NOW(3), INTERVAL 2 MINUTE) WHERE id=" + root.getId());
            }
            compensation.sweep();
            assertEquals(1, dispatchDao.listBySource(1L, "SCHEDULED_TASK_RUN", run.getId()).size());
            assertEquals(2, transport.count);
            assertEquals("DISPATCHED", dispatchDao.findById(root.getId()).getStatus());
        } finally {
            triggerService.setRunOrchestrator(null);
        }
    }

    @Test
    void degradedContinuousDescriptorKeepsCheckpointButDropsNativeSession() throws Exception {
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM dispatch_recovery_checkpoint WHERE tenant_id=1");
            statement.executeUpdate("DELETE FROM dispatch WHERE tenant_id=1 AND workitem_id IN (701,702)");
        }
        DispatchDO source = insertDispatch(701L, "continuous-source", null, null, "SUCCEEDED");
        DispatchDO replacement = insertDispatch(702L, "continuous-degraded", source.getId(), "DEGRADED_CONTINUOUS", "PENDING");
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        StoredObject object = storage.put("checkpoints", "source.tar.gz", "checkpoint".getBytes());
        DispatchCheckpointDO checkpoint = new DispatchCheckpointDO();
        checkpoint.setTenantId(1L); checkpoint.setWorkitemId(source.getWorkitemId()); checkpoint.setDispatchId(source.getId());
        checkpoint.setAgentId(40L); checkpoint.setCheckpointSeq(1L); checkpoint.setProvider("codex");
        checkpoint.setProviderSessionId("native-session-must-not-leak"); checkpoint.setExecutorId(501L);
        checkpoint.setOssRef(object.getOssRef()); checkpoint.setSha256("abc"); checkpoint.setSizeBytes(10L);
        sessionMapper(DispatchCheckpointDao.class).insert(checkpoint);
        OssProperties oss = new OssProperties(); oss.setArtifactBucket("checkpoints");
        DispatchCheckpointService checkpoints = new DispatchCheckpointService(sessionMapper(DispatchCheckpointDao.class),
                sessionMapper(DispatchRuntimeEventDao.class), dispatchDao, storage, oss);
        var descriptor = checkpoints.descriptor(dispatchDao.findById(replacement.getId()));
        assertNotNull(descriptor);
        assertEquals("DEGRADED_CONTINUOUS", descriptor.mode());
        assertEquals(null, descriptor.providerSessionId());
        assertNotNull(descriptor.checkpointDownloadUrl());
        assertEquals(1, descriptor.checkpointCandidates().size());
    }

    @Test
    void continuousRunTimeoutPersistsDegradationAndDispatchesCheckpointOnlyContinuation() throws Exception {
        resetActiveDueTask();
        seedExecutableAgentAndUnreadableRequirementDocument();
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM dispatch_recovery_checkpoint WHERE tenant_id=1");
            statement.executeUpdate("DELETE FROM dispatch WHERE tenant_id=1");
        }

        ScheduledTaskRunDO sourceRun = continuousRun("continuous-source", "SUCCEEDED",
                Instant.parse("2026-08-10T00:00:00Z"));
        runDao.insert(sourceRun);
        ScheduledTaskRunDO replacementRun = continuousRun("continuous-replacement", "QUEUED",
                Instant.parse("2026-08-10T00:01:00Z"));
        runDao.insert(replacementRun);
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE scheduled_task_run SET gmt_create='2026-08-10 00:00:01.000' WHERE id="
                    + replacementRun.getId());
        }

        DispatchDO source = insertDispatch(sourceRun.getId(), "continuous-source-dispatch", null, null, "SUCCEEDED");
        InMemoryObjectStorage checkpointStorage = new InMemoryObjectStorage();
        StoredObject stored = checkpointStorage.put("checkpoints", "continuous-source.tar.gz", "checkpoint".getBytes());
        DispatchCheckpointDO checkpoint = new DispatchCheckpointDO();
        checkpoint.setTenantId(1L); checkpoint.setWorkitemId(sourceRun.getId()); checkpoint.setDispatchId(source.getId());
        checkpoint.setAgentId(40L); checkpoint.setCheckpointSeq(1L); checkpoint.setProvider("codex");
        checkpoint.setProviderSessionId("native-source-session"); checkpoint.setExecutorId(501L);
        checkpoint.setOssRef(stored.getOssRef()); checkpoint.setSha256("abc"); checkpoint.setSizeBytes(10L);
        sessionMapper(DispatchCheckpointDao.class).insert(checkpoint);

        RedisManager redis = new RedisManager(new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379)), false);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        PresenceManager presence = new PresenceManager(redis, new NodeIdentity());
        // 501 is deliberately absent: only 502 can execute the checkpoint-only fallback.
        presence.register(502L, 40L, 2);
        ExecutorSelector selector = new ExecutorSelector(redis, registry, presence, dispatchDao);
        OssProperties oss = new OssProperties(); oss.setArtifactBucket("checkpoints");
        DispatchCheckpointService checkpoints = new DispatchCheckpointService(sessionMapper(DispatchCheckpointDao.class),
                sessionMapper(DispatchRuntimeEventDao.class), dispatchDao, checkpointStorage, oss);
        RecordingTransport transport = new RecordingTransport();
        DispatchService dispatchService = new DispatchService(dispatchDao,
                sessionMapper(DispatchRuntimeEventDao.class), sessionMapper(WorkitemDao.class), agentDao,
                agentVersionDao, selector, new PackageContextAssembler(new ExecutionSubjectRegistry(java.util.List.of(
                        new ScheduledRunExecutionSubjectProvider(runDao, artifactDao)))),
                new TaskPackager(new InMemoryObjectStorage(), "task-packages", "http://localhost"), transport,
                null, redis, null, new AuditLogService(sessionMapper(AuditLogDao.class), null, agentDao), registry);
        ScheduledTaskRunService runService = new ScheduledTaskRunService(runDao);
        dispatchService.setScheduledTaskRunService(runService);
        ScheduledTaskRunRecoveryService recovery = new ScheduledTaskRunRecoveryService(runDao, dispatchDao,
                selector, dispatchService, Clock.fixed(Instant.parse("2026-08-10T00:05:00Z"), ZoneOffset.UTC));
        recovery.setRunService(runService);
        recovery.setCheckpointService(checkpoints);

        ScheduledTaskRunRecoveryService.ResumePlan plan = recovery.reconcile(
                runDao.findById(1L, replacementRun.getId()));
        assertEquals(ScheduledTaskRunRecoveryService.State.DEGRADED, plan.state());
        ScheduledTaskRunDO degraded = runDao.findById(1L, replacementRun.getId());
        assertEquals(1, degraded.getDegradedResume());
        assertEquals("SOURCE_EXECUTOR_TIMEOUT", degraded.getDegradedReason());

        ScheduledTaskRunOrchestrator orchestrator = new ScheduledTaskRunOrchestrator(runDao, dispatchService);
        orchestrator.setDocumentDependencies(artifactDao, new EmptyObjectStorage());
        orchestrator.setRunService(runService);
        orchestrator.setRecoveryService(recovery);
        dispatchService.setScheduledTaskRunOrchestrator(orchestrator);
        orchestrator.start(1L, replacementRun.getId(), 7L);

        DispatchDO continuation = dispatchDao.listBySource(1L, "SCHEDULED_TASK_RUN", replacementRun.getId()).stream()
                .filter(row -> "DEGRADED_CONTINUOUS".equals(row.getResumeMode())).findFirst().orElseThrow();
        assertEquals(source.getId(), continuation.getResumeFromDispatchId());
        assertEquals(502L, continuation.getExecutorId());
        var descriptor = checkpoints.descriptor(continuation);
        assertNotNull(descriptor);
        assertEquals("DEGRADED_CONTINUOUS", descriptor.mode());
        assertEquals(null, descriptor.providerSessionId());
        assertNotNull(descriptor.checkpointDownloadUrl());
        assertEquals(1, descriptor.checkpointCandidates().size());
    }

    @Test
    void multiAgentRunCommentsAndArtifactsAreSourceScopedAtEqualNumericIds() throws Exception {
        long sharedId = 777L;
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM workitem_comment WHERE tenant_id=1 AND workitem_id=" + sharedId);
            statement.executeUpdate("DELETE FROM artifact WHERE tenant_id=1 AND workitem_id=" + sharedId);
        }
        WorkitemCommentDao comments = sessionMapper(WorkitemCommentDao.class);
        comments.insert(comment(sharedId, "WORKITEM", 40L, "legacy workitem comment"));
        comments.insert(comment(sharedId, "SCHEDULED_TASK_RUN", 40L, "agent A handoff"));
        comments.insert(comment(sharedId, "SCHEDULED_TASK_RUN", 41L, "agent B acknowledgement"));
        artifactDao.insert(artifact(sharedId, "WORKITEM", "legacy.txt"));
        artifactDao.insert(artifact(sharedId, "SCHEDULED_TASK_RUN", "agent-a-report.md"));
        artifactDao.insert(artifact(sharedId, "SCHEDULED_TASK_RUN", "agent-b-report.md"));

        assertEquals(1, comments.listBySource(1L, "WORKITEM", sharedId).size());
        var runComments = comments.listBySource(1L, "SCHEDULED_TASK_RUN", sharedId);
        assertEquals(2, runComments.size());
        assertTrue(runComments.stream().map(WorkitemCommentDO::getAuthorRef)
                .collect(java.util.stream.Collectors.toSet()).containsAll(java.util.Set.of(40L, 41L)));
        assertEquals(1, artifactDao.listBySource(1L, "WORKITEM", sharedId, null).size());
        assertEquals(2, artifactDao.listBySource(1L, "SCHEDULED_TASK_RUN", sharedId, null).size());
    }

    private static boolean uploadSameDocument(RequirementDocumentService documents, CountDownLatch ready,
                                              CountDownLatch start, String content) throws Exception {
        ready.countDown(); start.await();
        try {
            documents.uploadMcp(new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 100L), "spec.md",
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8), 1L, 7L, "race");
            return true;
        } catch (BizException expectedConflict) { return false; }
    }

    private static <T> T sessionMapper(Class<T> mapperType) {
        return sqlSession.getMapper(mapperType);
    }

    private static DispatchDO insertDispatch(long sourceId, String idempotencyKey, Long resumeFrom,
                                             String resumeMode, String status) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setTenantId(1L); dispatch.setSourceType("SCHEDULED_TASK_RUN"); dispatch.setWorkitemId(sourceId);
        dispatch.setAgentId(40L); dispatch.setAgentVersionId(41L); dispatch.setExecutorId(501L);
        dispatch.setAttempt(1); dispatch.setIdempotencyKey(idempotencyKey); dispatch.setStatus(status);
        dispatch.setResumeFromDispatchId(resumeFrom); dispatch.setResumeMode(resumeMode);
        dispatch.setCreatorId(7L); dispatch.setModifierId(7L); dispatch.setVersion(0);
        dispatchDao.insert(dispatch);
        return dispatch;
    }

    private static ScheduledTaskRunDO continuousRun(String triggerKey, String status, Instant scheduledAt) {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setWorkspaceId(1L); run.setScheduledTaskId(100L); run.setTriggerKey(triggerKey); run.setTriggerType("MANUAL");
        run.setScheduledAt(Date.from(scheduledAt)); run.setStatus(status); run.setSquadId(30L); run.setInitialAgentId(40L);
        run.setCurrentAgentId(40L); run.setSessionMode("CONTINUOUS"); run.setOwnerId(7L); run.setCreatorId(7L); run.setModifierId(7L);
        run.setExecutionSnapshotJson("{\"schemaVersion\":\"autowonder.scheduledTaskExecutionSnapshot.v1\","
                + "\"task\":{\"id\":100,\"name\":\"nightly\",\"instructionMd\":\"recover\"},"
                + "\"assignment\":{\"squadId\":30,\"initialAgentId\":40},"
                + "\"policies\":{\"sessionMode\":\"CONTINUOUS\",\"overlapPolicy\":\"SKIP\",\"affinityTimeoutSeconds\":1},"
                + "\"trigger\":{\"type\":\"MANUAL\",\"scheduledAt\":\"2026-08-10T00:00:00Z\"},"
                + "\"requirementDocuments\":[],\"agentContexts\":[{\"agentId\":40,\"agentVersionId\":41,"
                + "\"identity\":{\"name\":\"worker\",\"roleCode\":\"ops\"},\"repos\":[],\"repoMap\":{},"
                + "\"skills\":[],\"memory\":{},\"roster\":{}}]}");
        return run;
    }

    private static WorkitemCommentDO comment(long sourceId, String sourceType, long authorId, String content) {
        WorkitemCommentDO comment = new WorkitemCommentDO();
        comment.setTenantId(1L); comment.setSourceType(sourceType); comment.setWorkitemId(sourceId);
        comment.setAuthorType("AGENT"); comment.setAuthorRef(authorId); comment.setContentMd(content);
        return comment;
    }

    private static com.aliyun.autowonder.artifact.ArtifactDO artifact(long sourceId, String sourceType, String name) {
        com.aliyun.autowonder.artifact.ArtifactDO artifact = new com.aliyun.autowonder.artifact.ArtifactDO();
        artifact.setTenantId(1L); artifact.setSourceType(sourceType); artifact.setWorkitemId(sourceId);
        artifact.setName(name); artifact.setType("REPORT"); artifact.setOssRef("mem/" + sourceType + "/" + name); artifact.setSize(1L);
        return artifact;
    }

    private static RequirementDocumentService transactionalDocuments(SqlSessionTemplate session, AuditLogService audit,
                                                                      OssProperties oss) {
        RequirementDocumentService target = new RequirementDocumentService(artifactDao,
                session.getMapper(WorkitemDao.class), taskDao, documentStorage, audit, oss);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (RequirementDocumentService) proxy.getProxy();
    }

    private static ScheduledTaskScheduler schedulerAt(Instant now) {
        ScheduledTaskProperties properties = new ScheduledTaskProperties();
        properties.setEnabled(true);
        properties.setScanBatchSize(20);
        properties.setLockTtlSeconds(10);
        RedisManager redis = new RedisManager(new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379)), false);
        ScheduledTaskScheduler target = new ScheduledTaskScheduler(taskDao, triggerService,
                new ScheduledTaskSchedule(), redis, properties, Clock.fixed(now, ZoneOffset.UTC),
                scannerEnabledCapabilityGuard());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        return (ScheduledTaskScheduler) proxy.getProxy();
    }

    private static ScheduledTaskCapabilityGuard availableCapabilityGuard() {
        ScheduledTaskCapabilityGuard guard = mock(ScheduledTaskCapabilityGuard.class);
        when(guard.isAvailable()).thenReturn(true);
        return guard;
    }

    private static ScheduledTaskCapabilityGuard scannerEnabledCapabilityGuard() {
        ScheduledTaskCapabilityGuard guard = availableCapabilityGuard();
        when(guard.isScannerEnabled()).thenReturn(true);
        return guard;
    }

    private static ScheduledTaskRunOrchestrator recoveryOrchestrator(RecordingTransport transport) {
        RedisManager redis = new RedisManager(new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379)), false);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        PresenceManager presence = new PresenceManager(redis, new NodeIdentity());
        presence.register(503L, 40L, 2);
        DispatchService dispatchService = new DispatchService(dispatchDao, sessionMapper(DispatchRuntimeEventDao.class),
                sessionMapper(WorkitemDao.class), agentDao, agentVersionDao,
                new ExecutorSelector(redis, registry, presence, dispatchDao),
                new PackageContextAssembler(new ExecutionSubjectRegistry(java.util.List.of(
                        new ScheduledRunExecutionSubjectProvider(runDao, artifactDao)))),
                new TaskPackager(new InMemoryObjectStorage(), "task-packages", "http://localhost"), transport,
                null, redis, null, new AuditLogService(sessionMapper(AuditLogDao.class), null, agentDao), registry);
        ScheduledTaskRunService runService = new ScheduledTaskRunService(runDao);
        dispatchService.setScheduledTaskRunService(runService);
        ScheduledTaskRunOrchestrator orchestrator = new ScheduledTaskRunOrchestrator(runDao, dispatchService);
        orchestrator.setDocumentDependencies(artifactDao, new EmptyObjectStorage());
        orchestrator.setRunService(runService);
        dispatchService.setScheduledTaskRunOrchestrator(orchestrator);
        return orchestrator;
    }

    private static TriggerNode independentTriggerNode() throws Exception {
        DataSource nodeDataSource = new DriverManagerDataSource("jdbc:mysql://" + MYSQL.getHost() + ":"
                + MYSQL.getMappedPort(3306) + "/scheduled_spring?useSSL=false", "root", MYSQL.getPassword());
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(nodeDataSource);
        // Explicitly bind each independent mapper factory to its own Spring
        // transaction context; without this MyBatis can issue FOR UPDATE in
        // auto-commit mode and the test would not exercise the production lock.
        factoryBean.setTransactionFactory(new SpringManagedTransactionFactory());
        Configuration mybatis = new Configuration();
        mybatis.setDatabaseId(SOURCE_AWARE_DATABASE_ID);
        mybatis.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(mybatis);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapping/*.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factoryBean.getObject());
        ScheduledTaskDao nodeTaskDao = session.getMapper(ScheduledTaskDao.class);
        ScheduledTaskTriggerService nodeTrigger = new ScheduledTaskTriggerService(
                session.getMapper(ScheduledTaskRunDao.class), session.getMapper(ArtifactDao.class),
                session.getMapper(SquadMemberDao.class), session.getMapper(AgentDao.class),
                session.getMapper(AgentVersionDao.class), new EmptyObjectStorage());
        nodeTrigger.setTaskDao(nodeTaskDao);
        return new TriggerNode(new TransactionTemplate(new DataSourceTransactionManager(nodeDataSource)), nodeTaskDao, nodeTrigger);
    }

    private static void fireAfterBarrier(TriggerNode node, CountDownLatch ready, CountDownLatch start,
                                         Instant scheduledAt) {
        ready.countDown(); await(start);
        node.transaction.executeWithoutResult(status -> node.trigger.fireScheduled(
                node.taskDao.findById(1L, 100L), scheduledAt, scheduledAt));
    }

    private static void resetPausedTask() throws Exception {
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE scheduled_task SET status='PAUSED', next_fire_at='2026-01-01 00:00:00.000', last_fire_at=NULL, version=0 WHERE id=100 AND workspace_id=1");
            statement.executeUpdate("DELETE FROM audit_log WHERE tenant_id=1 AND target_id=100");
        }
    }

    private static void resetActiveDueTask() throws Exception {
        clearRedisTestState();
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM scheduled_task_run WHERE workspace_id=1 AND scheduled_task_id=100");
            statement.executeUpdate("DELETE FROM agent_version WHERE id=41");
            statement.executeUpdate("DELETE FROM artifact WHERE tenant_id=1 AND source_type='SCHEDULED_TASK' AND workitem_id=100");
            statement.executeUpdate("DELETE FROM scheduled_task WHERE id >= 200");
            statement.executeUpdate("UPDATE scheduled_task SET status='ACTIVE', cron_expression='0 0 2 * * *', next_fire_at='2026-08-10 18:00:00.000', last_fire_at=NULL, version=0 WHERE id=100 AND workspace_id=1");
        }
    }

    /**
     * The test schema uses DATETIME while this fixture intentionally runs its
     * JDBC driver in a different default zone.  Query binding therefore needs
     * an eight-hour-later scanner Clock.  A 04:00 cron keeps the next logical
     * occurrence after that Clock, so this remains a one-occurrence scanner
     * test rather than accidentally exercising FIRE_LATEST misfire behavior.
     */
    private static void prepareSingleOccurrenceScannerWindow() throws Exception {
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE scheduled_task SET cron_expression='0 0 4 * * *' WHERE workspace_id=1 AND id=100");
        }
    }

    private static Instant scannerNow(ScheduledTaskDO task) {
        return task.getNextFireAt().toInstant().plusSeconds(8 * 60 * 60L + 1);
    }

    /** Every assertion gets its own logical scheduler cluster; stale locks must never cross tests. */
    private static void clearRedisTestState() {
        RuntimeException failure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Jedis jedis = new Jedis(REDIS.getHost(), REDIS.getMappedPort(6379))) {
                jedis.flushDB(); return;
            } catch (RuntimeException transientStartup) {
                failure = transientStartup;
                try { Thread.sleep(100L * (attempt + 1)); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw transientStartup;
                }
            }
        }
        throw failure;
    }

    private static void seedExecutableAgentAndUnreadableRequirementDocument() throws Exception {
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO agent_version(id, tenant_id, agent_id, version_no, status, role_name, role_code, is_deleted, version) VALUES (41, 1, 40, 1, 'APPROVED', 'worker', 'ops', 0, 0)");
            statement.executeUpdate("INSERT INTO artifact(tenant_id, source_type, workitem_id, name, type, oss_ref, size) VALUES (1, 'SCHEDULED_TASK', 100, 'requirements/fault.md', 'REQUIREMENT_DOC', 'missing/fault.md', 1)");
        }
    }

    private static void seedExecutableAgent() throws Exception {
        try (Connection connection = fixture.open("scheduled_spring"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO agent_version(id, tenant_id, agent_id, version_no, status, role_name, role_code, is_deleted, version) VALUES (41, 1, 40, 1, 'APPROVED', 'worker', 'ops', 0, 0)");
        }
    }

    private static String sha256(byte[] bytes) {
        assertNotNull(bytes);
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void assertPausedOriginal() {
        ScheduledTaskDO task = taskDao.findById(1L, 100L);
        assertEquals("PAUSED", task.getStatus());
        assertEquals(0, task.getVersion());
        // MySQL DATETIME is read in the JVM's configured +08 zone in this build;
        // this is the same persisted cursor inserted by resetPausedTask, not the
        // recomputed 2026-08 scheduler cursor.
        assertEquals(Instant.parse("2025-12-31T16:00:00Z"), task.getNextFireAt().toInstant());
    }

    private static void seed(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO squad(id, tenant_id, name, status, is_deleted, version) VALUES (30, 1, 'ops', 0, 0, 0)");
            statement.executeUpdate("INSERT INTO agent(id, tenant_id, name, status, online_version_id, is_deleted, version) VALUES (40, 1, 'worker', 'ONLINE', 41, 0, 0)");
            statement.executeUpdate("INSERT INTO squad_member(tenant_id, squad_id, agent_id) VALUES (1, 30, 40)");
            statement.executeUpdate("INSERT INTO scheduled_task(id, workspace_id, name, instruction_md, squad_id, initial_agent_id, schedule_type, cron_expression, timezone, session_mode, overlap_policy, misfire_policy, start_deadline_seconds, affinity_timeout_seconds, status, next_fire_at, gmt_create, creator_id, is_deleted, version) VALUES (100, 1, 'nightly', 'run', 30, 40, 'CRON', '0 0 2 * * *', 'Asia/Shanghai', 'ISOLATED', 'SKIP', 'FIRE_LATEST', 21600, 1800, 'PAUSED', '2026-01-01 00:00:00.000', '2025-12-31 00:00:00.000', 7, 0, 0)");
        }
    }

    private static final class EmptyObjectStorage implements ObjectStorage {
        @Override public StoredObject put(String bucket, String key, byte[] data) { throw new UnsupportedOperationException(); }
        @Override public byte[] get(String ossRef) { return null; }
        @Override public String presignGet(String ossRef, int ttlSeconds) { return null; }
        @Override public boolean exists(String ossRef) { return false; }
        @Override public void delete(String ossRef) { }
    }

    private static final class InMemoryStorage implements ObjectStorage {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        @Override public StoredObject put(String bucket, String key, byte[] data) {
            String ref = bucket + "/" + key; objects.put(ref, data.clone()); return new StoredObject(ref, "test", data.length);
        }
        @Override public byte[] get(String ossRef) { return objects.get(ossRef); }
        @Override public String presignGet(String ossRef, int ttlSeconds) { return ossRef; }
        @Override public boolean exists(String ossRef) { return objects.containsKey(ossRef); }
        @Override public void delete(String ossRef) { objects.remove(ossRef); }
        void clear() { objects.clear(); }
        int size() { return objects.size(); }
    }

    private static final class RecordingTransport implements DispatchTransport {
        private int count;
        @Override public void dispatch(DispatchDO dispatch, TaskPackageResult taskPackage) { count++; }
    }

    private record TriggerNode(TransactionTemplate transaction, ScheduledTaskDao taskDao,
                               ScheduledTaskTriggerService trigger) { }
}
