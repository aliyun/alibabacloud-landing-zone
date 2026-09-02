package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.audit.AuditLogDao;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.scheduledtask.dto.CreateScheduledTaskRequest;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityAspect;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;
import com.aliyun.autowonder.scheduledtask.compat.V037CompatibilityConfiguration;
import com.aliyun.autowonder.scheduledtask.compat.V037CompatibilityMetrics;
import com.aliyun.autowonder.scheduledtask.compat.V037DatabaseIdProvider;
import com.aliyun.autowonder.scheduledtask.compat.V037MapperMode;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaCapability;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaCapabilityClassifier;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaCapabilityDetector;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaMode;
import com.aliyun.autowonder.squad.SquadMemberDao;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.codahale.metrics.MetricRegistry;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact real-MySQL matrix for every supported V037 deployment window. */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V037CompatibilityMatrixTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("test").withUsername("test").withPassword("test");

    private final ScheduledTaskIntegrationFixture fixture = new ScheduledTaskIntegrationFixture(MYSQL);

    @Test
    void classifiesTheExactFiveStateDeploymentMatrixAndFailsStartupOnPersistedInconsistency()
            throws Exception {
        createPreV037("matrix_legacy");

        createPreV037("matrix_partial_shared");
        execute("matrix_partial_shared", "ALTER TABLE dispatch "
                + "ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'WORKITEM' AFTER tenant_id, "
                + "ADD KEY idx_dispatch_source (tenant_id, source_type, workitem_id, id)");

        createFullV037("matrix_missing_scheduled_index");
        execute("matrix_missing_scheduled_index",
                "ALTER TABLE scheduled_task_run DROP INDEX idx_scheduled_task_run_health");

        createFullV037("matrix_ready");

        createFullV037("matrix_inconsistent");
        execute("matrix_inconsistent", "INSERT INTO scheduled_task "
                + "(workspace_id,name,instruction_md,squad_id,initial_agent_id,schedule_type,run_at,timezone,"
                + "session_mode,overlap_policy,misfire_policy,status,creator_id) VALUES "
                + "(1,'persisted','run',1,1,'ONCE','2026-08-18 00:00:00.000','UTC',"
                + "'ISOLATED','SKIP','FIRE_LATEST','ACTIVE',1)");
        execute("matrix_inconsistent", "ALTER TABLE workitem "
                + "DROP INDEX idx_workitem_origin, DROP COLUMN origin_id");

        assertCapability("matrix_legacy", V037SchemaMode.LEGACY,
                V037MapperMode.LEGACY, false, false, false);
        V037SchemaCapability partialShared = assertCapability("matrix_partial_shared",
                V037SchemaMode.V037_PARTIAL, V037MapperMode.LEGACY, false, false, false);
        assertTrue(partialShared.missingObjects().contains("artifact.source_type"));

        V037SchemaCapability missingIndex = assertCapability("matrix_missing_scheduled_index",
                V037SchemaMode.V037_PARTIAL, V037MapperMode.SOURCE_AWARE, false, true, false);
        assertTrue(missingIndex.missingObjects().contains(
                "scheduled_task_run.idx_scheduled_task_run_health"));

        V037SchemaCapability ready = assertCapability("matrix_ready", V037SchemaMode.V037_READY,
                V037MapperMode.SOURCE_AWARE, true, true, false);
        assertTrue(ready.missingObjects().isEmpty());

        V037SchemaCapability inconsistent = assertCapability("matrix_inconsistent",
                V037SchemaMode.INCONSISTENT, V037MapperMode.LEGACY, false, false, true);
        assertTrue(inconsistent.missingObjects().contains("workitem.origin_id"));
        IllegalStateException startupFailure = assertThrows(IllegalStateException.class,
                () -> new V037CompatibilityConfiguration().v037SchemaCapability(
                        dataSource("matrix_inconsistent")));
        assertTrue(startupFailure.getMessage().contains("unsafe V037 schema state"));
    }

    @Test
    void onlineDdlKeepsTheFrozenLegacyNodeUsableAndOnlyANewFactoryChangesMode()
            throws Exception {
        String database = "matrix_online_ddl";
        createPreV037(database);
        V037SchemaCapability frozenCapability = detect(database);
        try (NodeContext frozenLegacy = openNode(database, frozenCapability)) {
            assertEquals("autowonder-legacy", frozenLegacy.databaseId());
            completeOrdinaryWorkitem(frozenLegacy, "before-ddl");

            try (Connection migration = fixture.open(database)) {
                fixture.applyFile(migration, "docs/migration/V041__scheduled_task.sql");
            }

            completeOrdinaryWorkitem(frozenLegacy, "after-ddl");
            assertThrows(BizException.class, () -> guard(frozenCapability, true, true, true)
                    .requireAvailable("scheduler"));
            assertEquals("autowonder-legacy", frozenLegacy.databaseId(),
                    "a serving node must not switch mapper SQL beneath live traffic");
        }

        V037SchemaCapability restartedCapability = detect(database);
        SqlSessionFactory restarted = buildFactory(database, restartedCapability);
        assertEquals(V037SchemaMode.V037_READY, restartedCapability.mode());
        assertEquals("autowonder-source-aware", restarted.getConfiguration().getDatabaseId());
    }

    @Test
    void mixedFrozenNodesServeWorkitemsUntilAttestationThenAnInternalManualRunIsAllowed()
            throws Exception {
        String database = "matrix_mixed_nodes";
        createPreV037(database);
        V037SchemaCapability legacyCapability = detect(database);
        NodeContext legacy = openNode(database, legacyCapability);
        NodeContext sourceAware = null;
        try {
            try (Connection migration = fixture.open(database)) {
                fixture.applyFile(migration, "docs/migration/V041__scheduled_task.sql");
            }
            V037SchemaCapability sourceAwareCapability = detect(database);
            sourceAware = openNode(database, sourceAwareCapability);
            assertEquals("autowonder-legacy", legacy.databaseId());
            assertEquals("autowonder-source-aware", sourceAware.databaseId());

            completeOrdinaryWorkitem(legacy, "legacy-node");
            completeOrdinaryWorkitem(sourceAware, "source-aware-node");
            assertScheduledCreateRejected(legacy, guard(legacyCapability, true, false, false));
            assertScheduledCreateRejected(sourceAware,
                    guard(sourceAwareCapability, true, false, false));
            try (Connection connection = fixture.open(database)) {
                assertEquals(0, fixture.count(connection, "SELECT COUNT(*) FROM scheduled_task"));
                assertEquals(0, fixture.count(connection, "SELECT COUNT(*) FROM scheduled_task_run"));
            }

            // Drain and close the final Legacy serving session before attestation.
            legacy.close();
            legacy = null;

            seedManualRunContext(database);
            ScheduledTaskCapabilityGuard attested = guard(sourceAwareCapability, true, false, true);
            assertTrue(attested.isAvailable());
            assertFalse(attested.isScannerEnabled(),
                    "manual smoke testing must not accidentally start the scanner");
            ScheduledTaskRunDO run = fireManualInSpringTransaction(
                    database, sourceAwareCapability, attested);
            assertEquals("MANUAL", run.getTriggerType());
            assertEquals("QUEUED", run.getStatus());
            assertEquals("task:713:manual:rollout-smoke", run.getTriggerKey());
            try (Connection connection = fixture.open(database)) {
                assertEquals(1, fixture.count(connection,
                        "SELECT COUNT(*) FROM scheduled_task_run WHERE workspace_id=71 AND id="
                                + run.getId()));
            }
        } finally {
            if (sourceAware != null) {
                sourceAware.close();
            }
            if (legacy != null) {
                legacy.close();
            }
        }
    }

    private V037SchemaCapability assertCapability(String database, V037SchemaMode mode,
            V037MapperMode mapperMode, boolean available, boolean sourceAware, boolean evidence) {
        V037SchemaCapability capability = detect(database);
        assertEquals(mode, capability.mode(), capability.missingObjects().toString());
        assertEquals(mapperMode, capability.mapperMode());
        assertEquals(available, capability.scheduledAvailable());
        assertEquals(sourceAware, capability.sourceAwareColumnsReady());
        assertEquals(evidence, capability.scheduledDataExists());
        return capability;
    }

    private SqlSessionFactory buildFactory(String database, V037SchemaCapability capability)
            throws Exception {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(dataSource(database));
        bean.setDatabaseIdProvider(new V037DatabaseIdProvider(capability));
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(configuration);
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapping/*.xml"));
        return bean.getObject();
    }

    private NodeContext openNode(String database, V037SchemaCapability capability)
            throws Exception {
        SqlSessionFactory factory = buildFactory(database, capability);
        return new NodeContext(capability, factory, factory.openSession(true));
    }

    private void completeOrdinaryWorkitem(NodeContext node, String suffix) {
        SqlSession session = node.session();
        WorkitemDO workitem = new WorkitemDO();
        workitem.setTenantId(71L);
        workitem.setWorkType("TASK");
        workitem.setTitle("rolling-" + suffix);
        workitem.setContentMd("ordinary workitem remains available");
        workitem.setPriority(0);
        workitem.setCreatorId(7L);
        WorkitemDao workitems = session.getMapper(WorkitemDao.class);
        workitems.insert(workitem);
        assertTrue(workitem.getId() != null);

        DispatchDO dispatch = new DispatchDO();
        dispatch.setTenantId(71L);
        dispatch.setSourceType("WORKITEM");
        dispatch.setWorkitemId(workitem.getId());
        dispatch.setAgentId(711L);
        dispatch.setStatus("PENDING");
        dispatch.setAttempt(0);
        String rawKey = workitem.getId() + ":1:0";
        dispatch.setIdempotencyKey(rawKey);
        dispatch.setCreatorId(7L);
        dispatch.setVersion(0);
        DispatchDao dispatches = session.getMapper(DispatchDao.class);
        dispatches.insert(dispatch);
        assertEquals(1, dispatches.updateStatus(dispatch.getId(), 71L, "SUCCEEDED",
                null, null, null, "complete", null, 0, 7L));
        DispatchDO completed = dispatches.findById(dispatch.getId());
        assertEquals("SUCCEEDED", completed.getStatus());
        assertEquals("WORKITEM", completed.getSourceType());
        assertEquals("rolling-" + suffix, workitems.findById(workitem.getId()).getTitle());
    }

    private void assertScheduledCreateRejected(NodeContext node,
            ScheduledTaskCapabilityGuard guard) {
        SqlSession session = node.session();
        AgentDao agents = session.getMapper(AgentDao.class);
        ScheduledTaskService service = new ScheduledTaskService(
                session.getMapper(ScheduledTaskDao.class), session.getMapper(SquadDao.class),
                session.getMapper(SquadMemberDao.class), agents,
                new AuditLogService(session.getMapper(AuditLogDao.class), null, agents),
                new ScheduledTaskSchedule());
        ScheduledTaskController target = new ScheduledTaskController(service,
                session.getMapper(ScheduledTaskRunDao.class), null);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new ScheduledTaskCapabilityAspect(guard));
        ScheduledTaskController guarded = proxyFactory.getProxy();

        CreateScheduledTaskRequest request = new CreateScheduledTaskRequest();
        request.setName("must-not-be-created");
        request.setInstructionMd("guard this create boundary");
        request.setSquadId(710L);
        request.setInitialAgentId(711L);
        request.setScheduleType("ONCE");
        request.setRunAt(Date.from(Instant.parse("2026-08-19T00:00:00Z")));
        request.setTimezone("UTC");
        request.setSessionMode("ISOLATED");
        request.setOverlapPolicy("SKIP");
        request.setMisfirePolicy("FIRE_LATEST");
        AutoWonderContext.get().setCurrentWorkspaceId(71L);
        AutoWonderContext.get().setUserId(7L);
        try {
            BizException failure = assertThrows(BizException.class,
                    () -> guarded.create(request));
            assertEquals("30006", failure.getCode());
        } finally {
            AutoWonderContext.destroy();
        }
    }

    private ScheduledTaskRunDO fireManualInSpringTransaction(String database,
            V037SchemaCapability capability, ScheduledTaskCapabilityGuard guard) throws Exception {
        DataSource source = dataSource(database);
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setTransactionFactory(new SpringManagedTransactionFactory());
        bean.setDatabaseIdProvider(new V037DatabaseIdProvider(capability));
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(configuration);
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapping/*.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(bean.getObject());
        ScheduledTaskTriggerService target = new ScheduledTaskTriggerService(
                session.getMapper(ScheduledTaskRunDao.class), session.getMapper(ArtifactDao.class),
                session.getMapper(SquadMemberDao.class), session.getMapper(AgentDao.class),
                session.getMapper(AgentVersionDao.class), new InMemoryObjectStorage());
        target.setTaskDao(session.getMapper(ScheduledTaskDao.class));
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new ScheduledTaskCapabilityAspect(guard));
        ScheduledTaskTriggerService guarded = proxyFactory.getProxy();
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        return transaction.execute(status -> guarded.fireManual(71L, 713L, "rollout-smoke"));
    }

    private ScheduledTaskCapabilityGuard guard(V037SchemaCapability capability,
            boolean enabled, boolean scannerEnabled, boolean attested) {
        ScheduledTaskProperties properties = new ScheduledTaskProperties();
        properties.setEnabled(enabled);
        properties.setScannerEnabled(scannerEnabled);
        properties.setClusterReadyAttestation(attested);
        return new ScheduledTaskCapabilityGuard(capability, properties,
                new V037CompatibilityMetrics(new MetricRegistry(), capability));
    }

    private void seedManualRunContext(String database) throws Exception {
        try (Connection connection = fixture.open(database);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO org(id,name,slug,owner_id) "
                    + "VALUES (71,'rolling-org','rolling-org',7)");
            statement.executeUpdate("INSERT INTO squad(id,tenant_id,name,status,is_deleted,version) "
                    + "VALUES (710,71,'rollout',0,0,0)");
            statement.executeUpdate("INSERT INTO agent(id,tenant_id,name,status,online_version_id,is_deleted,version) "
                    + "VALUES (711,71,'worker','ONLINE',712,0,0)");
            statement.executeUpdate("INSERT INTO agent_version"
                    + "(id,tenant_id,agent_id,version_no,status,role_name,role_code,is_deleted,version) "
                    + "VALUES (712,71,711,1,'APPROVED','worker','rollout',0,0)");
            statement.executeUpdate("INSERT INTO squad_member(tenant_id,squad_id,agent_id) "
                    + "VALUES (71,710,711)");
            statement.executeUpdate("INSERT INTO scheduled_task"
                    + "(id,workspace_id,name,instruction_md,squad_id,initial_agent_id,schedule_type,run_at,"
                    + "timezone,session_mode,overlap_policy,misfire_policy,status,creator_id) VALUES "
                    + "(713,71,'smoke','run',710,711,'ONCE','2026-08-19 00:00:00.000','UTC',"
                    + "'ISOLATED','SKIP','FIRE_LATEST','ACTIVE',7)");
        }
    }

    private void createPreV037(String database) throws Exception {
        fixture.createDatabase(database);
        try (Connection connection = fixture.open(database)) {
            fixture.applyClasspath(connection, "schema/autowonder-pre-v037.sql");
        }
    }

    private void createFullV037(String database) throws Exception {
        createPreV037(database);
        try (Connection connection = fixture.open(database)) {
            fixture.applyFile(connection, "docs/migration/V041__scheduled_task.sql");
        }
    }

    private void execute(String database, String sql) throws Exception {
        try (Connection connection = fixture.open(database);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private V037SchemaCapability detect(String database) {
        return new V037SchemaCapabilityDetector(new V037SchemaCapabilityClassifier())
                .detect(dataSource(database));
    }

    private DataSource dataSource(String database) {
        return new DriverManagerDataSource(
                "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                        + "/" + database + "?useUnicode=true&characterEncoding=utf-8&useSSL=false",
                "root", MYSQL.getPassword());
    }

    private record NodeContext(V037SchemaCapability capability,
                               SqlSessionFactory factory,
                               SqlSession session) implements AutoCloseable {
        String databaseId() {
            return factory.getConfiguration().getDatabaseId();
        }

        @Override
        public void close() {
            session.close();
        }
    }
}
