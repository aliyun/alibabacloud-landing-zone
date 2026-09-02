package com.aliyun.autowonder.scheduledtask;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.artifact.ArtifactOwnerRef;
import com.aliyun.autowonder.artifact.ArtifactService;
import com.aliyun.autowonder.artifact.RequirementDocumentService;
import com.aliyun.autowonder.artifact.dto.ReportArtifactRequest;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.dashboard.DashboardDao;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.guidance.GuidanceDO;
import com.aliyun.autowonder.guidance.GuidanceDao;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityAspect;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;
import com.aliyun.autowonder.scheduledtask.compat.V037DatabaseIdProvider;
import com.aliyun.autowonder.scheduledtask.compat.V037CompatibilityMetrics;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaCapability;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaCapabilityClassifier;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaCapabilityDetector;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaMode;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import com.aliyun.autowonder.workitem.WorkitemCommentMentionDO;
import com.aliyun.autowonder.workitem.WorkitemCommentMentionDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.codahale.metrics.MetricRegistry;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V037LegacyWorkitemIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("test").withUsername("test").withPassword("test");

    private final ScheduledTaskIntegrationFixture fixture = new ScheduledTaskIntegrationFixture(MYSQL);
    private final SqlFailureRecorder sqlFailures = new SqlFailureRecorder();
    private V037SchemaCapability capability;
    private SqlSessionTemplate session;

    @BeforeAll
    void setUp() throws Exception {
        fixture.createDatabase("pre_v037_workitems");
        try (Connection connection = fixture.open("pre_v037_workitems")) {
            fixture.applyClasspath(connection, "schema/autowonder-pre-v037.sql");
        }
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                        + "/pre_v037_workitems?useUnicode=true&characterEncoding=utf-8&useSSL=false",
                "root", MYSQL.getPassword());
        capability = new V037SchemaCapabilityDetector(new V037SchemaCapabilityClassifier()).detect(dataSource);
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setDatabaseIdProvider(new V037DatabaseIdProvider(capability));
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(configuration);
        bean.setPlugins(sqlFailures);
        bean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapping/*.xml"));
        SqlSessionFactory factory = bean.getObject();
        session = new SqlSessionTemplate(factory);
    }

    @Test
    void exactCanonicalPreV037SchemaFreezesProductionMybatisInLegacyMode() throws Exception {
        assertEquals(V037SchemaMode.LEGACY, capability.mode());
        assertEquals("autowonder-legacy", session.getConfiguration().getDatabaseId());
        assertFalse(capability.scheduledAvailable());
        try (Connection connection = fixture.open("pre_v037_workitems")) {
            assertEquals(0, fixture.count(connection,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                            + "AND table_name IN ('scheduled_task','scheduled_task_run')"));
            assertFalse(fixture.hasColumn(connection, "dispatch", "source_type"));
            assertFalse(fixture.hasColumn(connection, "dispatch", "normalized_idempotency_key"));
            assertFalse(fixture.hasColumn(connection, "artifact", "source_type"));
            assertFalse(fixture.hasColumn(connection, "workitem_comment", "source_type"));
            assertFalse(fixture.hasColumn(connection, "workitem_comment_mention", "source_type"));
            assertFalse(fixture.hasColumn(connection, "workitem_comment_delivery", "source_type"));
            assertFalse(fixture.hasColumn(connection, "workitem", "origin_type"));
            assertFalse(fixture.hasColumn(connection, "workitem", "origin_id"));
            assertTrue(fixture.hasColumn(connection, "dispatch", "delivery_source_dispatch_id"));
            assertTrue(fixture.hasColumn(connection, "artifact", "meta_json"));
            assertTrue(fixture.hasColumn(connection, "workitem_comment_delivery", "reply_comment_id"));
            assertTrue(fixture.hasIndex(connection, "workitem_comment_delivery",
                    "idx_comment_delivery_reply", "tenant_id", "reply_comment_id"));
        }
    }

    @Test
    void ordinaryWorkitemProductionFlowsRemainUsableBeforeV037() throws Exception {
        seedDashboardReferences();
        WorkitemDao workitems = mapper(WorkitemDao.class);
        WorkitemDO workitem = workitem();
        workitems.insert(workitem);
        assertNotNull(workitem.getId());
        assertEquals(1, workitems.list(7L, null, null, null, null, null,
                false, null, 7L, null, null, null, 0, 20).size());
        assertEquals(1, workitems.count(7L, null, null, null, null, null,
                false, null, 7L, null, null, null));
        assertEquals(1, workitems.updateContent(workitem.getId(), 7L,
                "legacy-updated", "updated", 0, 7L));
        assertEquals("legacy-updated", workitems.findById(workitem.getId()).getTitle());
        Tenant8Poison poison = seedTenant8Poison(workitem.getId());
        assertEquals(1, workitems.list(7L, null, null, null, null, null,
                false, null, 7L, null, null, null, 0, 20).size());
        assertEquals(0, workitems.updateContent(poison.workitemId(), 7L,
                "cross-tenant-write", "forbidden", 0, 7L));

        exerciseArtifacts(workitems, workitem.getId(), poison);
        exerciseInteractions(workitem.getId(), poison);
        DispatchDO latest = exerciseDispatchLifecycle(workitems, workitem.getId(), poison);
        exerciseAggregateQueries(workitems, workitem.getId(), latest, poison);
        assertWorkspace8PoisonUnchanged(poison);

        assertTrue(sqlFailures.failures.isEmpty(), () -> "production mapper SQL failures: " + sqlFailures.failures);
        assertTrue(sqlFailures.failures.stream().noneMatch(message -> message.contains("Unknown column")));
        assertTrue(sqlFailures.failures.stream().noneMatch(message -> message.contains("doesn't exist")));
    }

    @Test
    void allEnabledConfigurationStillFailsClosedOnLocalLegacySchema() {
        ScheduledTaskProperties properties = new ScheduledTaskProperties();
        properties.setEnabled(true);
        properties.setScannerEnabled(true);
        properties.setClusterReadyAttestation(true);
        ScheduledTaskCapabilityGuard guard = new ScheduledTaskCapabilityGuard(capability, properties,
                new V037CompatibilityMetrics(new MetricRegistry(), capability));

        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ScheduledTaskTriggerService triggerService = mock(ScheduledTaskTriggerService.class);
        ScheduledTaskController target = new ScheduledTaskController(taskService, runDao, triggerService);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new ScheduledTaskCapabilityAspect(guard));
        ScheduledTaskController controller = proxyFactory.getProxy();
        BizException failure = assertThrows(BizException.class,
                () -> controller.list(null, null, null, null, 20, 0));
        assertEquals("30006", failure.getCode());
        verifyNoInteractions(taskService, runDao, triggerService);

        RedisManager schedulerRedis = mock(RedisManager.class);
        ScheduledTaskDao scheduledTaskDao = mock(ScheduledTaskDao.class);
        ScheduledTaskScheduler scheduler = new ScheduledTaskScheduler(scheduledTaskDao, triggerService,
                new ScheduledTaskSchedule(), schedulerRedis, properties, java.time.Clock.systemUTC(), guard);
        scheduler.scan();
        verifyNoInteractions(schedulerRedis, scheduledTaskDao, triggerService);

        RedisManager compensationRedis = mock(RedisManager.class);
        ScheduledTaskRunOrchestrator orchestrator = mock(ScheduledTaskRunOrchestrator.class);
        ScheduledTaskRunRecoveryService recovery = mock(ScheduledTaskRunRecoveryService.class);
        ScheduledTaskRunService runService = mock(ScheduledTaskRunService.class);
        ScheduledTaskRunCompensationTask compensation = new ScheduledTaskRunCompensationTask(
                runDao, orchestrator, recovery, compensationRedis, runService, guard);
        compensation.sweep();
        verifyNoInteractions(compensationRedis, runDao, orchestrator, recovery, runService);
    }

    private void exerciseArtifacts(WorkitemDao workitems, long workitemId, Tenant8Poison poison) {
        ArtifactDao artifactDao = mapper(ArtifactDao.class);
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        ArtifactService artifacts = new ArtifactService(artifactDao, storage);
        byte[] outputBytes = "# output".getBytes(StandardCharsets.UTF_8);
        var stored = storage.put("legacy-artifacts", "t/7/workitem/" + workitemId + "/output.md", outputBytes);
        ReportArtifactRequest output = new ReportArtifactRequest();
        output.setWorkitemId(workitemId);
        output.setName("artifacts/output/output.md");
        output.setType("REPORT");
        output.setOssRef(stored.getOssRef());
        output.setSize(stored.getSize());
        long outputId = artifacts.record(output, 7L);
        ArtifactDO persistedOutput = artifactDao.findWorkitemByTenantAndId(7L, outputId);
        assertWorkitemSource(persistedOutput.getSourceType());
        assertEquals(1, artifacts.listByWorkitem(workitemId, 7L).size());
        assertTrue(artifacts.getDownloadUrl(outputId,
                new ArtifactOwnerRef(ExecutionSourceType.WORKITEM, workitemId), 7L).startsWith("mem://"));
        assertEquals(1, artifactDao.deleteById(7L, outputId));
        assertNull(artifactDao.findWorkitemByTenantAndId(7L, outputId));
        assertThrows(BizException.class, () -> artifacts.getDownloadUrl(poison.artifactId(), 7L));
        assertEquals(0, artifactDao.deleteById(7L, poison.artifactId()));

        OssProperties oss = new OssProperties();
        oss.setArtifactBucket("legacy-artifacts");
        RequirementDocumentService requirements = new RequirementDocumentService(artifactDao, workitems,
                mock(ScheduledTaskDao.class), storage, mock(AuditLogService.class), oss);
        var requirement = requirements.uploadMcp(workitemId, "requirement.md",
                "# requirement".getBytes(StandardCharsets.UTF_8), 7L, 7L, "/tmp/requirement.md");
        ArtifactDO persistedRequirement = artifactDao.findWorkitemByTenantAndId(7L, requirement.getId());
        assertWorkitemSource(persistedRequirement.getSourceType());
        assertEquals(1, requirements.list(workitemId, 7L).size());
        assertTrue(artifacts.getDownloadUrl(requirement.getId(),
                new ArtifactOwnerRef(ExecutionSourceType.WORKITEM, workitemId), 7L).startsWith("mem://"));
        requirements.delete(workitemId, requirement.getId(), 7L, 7L);
        assertTrue(requirements.list(workitemId, 7L).isEmpty());
    }

    private void exerciseInteractions(long workitemId, Tenant8Poison poison) {
        WorkitemCommentDao comments = mapper(WorkitemCommentDao.class);
        WorkitemCommentDO human = comment(workitemId, "HUMAN", 7L, "please continue");
        WorkitemCommentDO agent = comment(workitemId, "AGENT", 40L, "acknowledged");
        comments.insert(human);
        comments.insert(agent);
        List<WorkitemCommentDO> commentRows = comments.listByWorkitem(7L, workitemId);
        assertEquals(2, commentRows.size());
        commentRows.forEach(row -> assertWorkitemSource(row.getSourceType()));
        assertTrue(commentRows.stream().noneMatch(row -> row.getId().equals(poison.commentId())));

        WorkitemCommentMentionDao mentions = mapper(WorkitemCommentMentionDao.class);
        WorkitemCommentMentionDO mention = new WorkitemCommentMentionDO();
        mention.setTenantId(7L);
        mention.setSourceType("WORKITEM");
        mention.setWorkitemId(workitemId);
        mention.setCommentId(human.getId());
        mention.setTargetType("AGENT");
        mention.setTargetRef(40L);
        mention.setDisplayNameSnapshot("worker-a");
        mentions.insert(mention);
        List<WorkitemCommentMentionDO> mentionRows = mentions.listByWorkitem(7L, workitemId);
        assertEquals(1, mentionRows.size());
        assertWorkitemSource(mentionRows.get(0).getSourceType());
        assertTrue(mentionRows.stream().noneMatch(row -> row.getId().equals(poison.mentionId())));

        GuidanceDao guidance = mapper(GuidanceDao.class);
        GuidanceDO delivery = new GuidanceDO();
        delivery.setTenantId(7L);
        delivery.setSourceType("WORKITEM");
        delivery.setWorkitemId(workitemId);
        delivery.setCommentId(human.getId());
        delivery.setTargetAgentId(40L);
        delivery.setStatus("QUEUED");
        guidance.insert(delivery);
        assertWorkitemSource(guidance.listByWorkitem(7L, workitemId).get(0).getSourceType());
        assertEquals(1, guidance.bindPendingDispatch(delivery.getId(), 7L, 999L));
        assertEquals(1, guidance.bindDispatch(delivery.getId(), 7L, 999L, 501L));
        assertEquals(1, guidance.updateStatus(delivery.getId(), 7L, "DELIVERED", null));
        assertEquals(1, guidance.acknowledge(delivery.getId(), 7L, 501L, "APPLIED", null));
        assertEquals(1, guidance.bindReplyComment(delivery.getId(), 7L, agent.getId()));
        GuidanceDO replied = guidance.findById(delivery.getId());
        assertEquals(agent.getId(), replied.getReplyCommentId());
        assertWorkitemSource(replied.getSourceType());
        assertTrue(guidance.listByWorkitem(7L, workitemId).stream()
                .noneMatch(row -> row.getId().equals(poison.guidanceId())));
        assertEquals(0, guidance.acknowledge(poison.guidanceId(), 7L, 8501L, "APPLIED", null));
    }

    private DispatchDO exerciseDispatchLifecycle(WorkitemDao workitems, long workitemId,
                                                  Tenant8Poison poison) {
        DispatchDao dispatchDao = mapper(DispatchDao.class);
        DispatchRuntimeEventDao runtimeEvents = mapper(DispatchRuntimeEventDao.class);
        DispatchService dispatches = new DispatchService(dispatchDao, runtimeEvents, workitems,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        DispatchDO first = dispatches.enqueueSubject(7L, ExecutionSourceType.WORKITEM,
                workitemId, 300L, 40L, 0, 7L);
        assertWorkitemSource(dispatchDao.findById(first.getId()).getSourceType());
        dispatches.onAck(7L, poison.dispatchId());
        complete(dispatches, dispatchDao, first);

        DispatchDO second = dispatches.enqueueHandoff(7L, workitemId, 301L, 41L,
                first.getId(), 7L);
        assertEquals(first.getId(), second.getDeliverySourceDispatchId());
        complete(dispatches, dispatchDao, second);
        DispatchDO third = dispatches.enqueueHandoff(7L, workitemId, 302L, 40L,
                second.getId(), 7L);
        assertEquals(second.getId(), third.getDeliverySourceDispatchId());
        complete(dispatches, dispatchDao, third);

        List<DispatchDO> rows = dispatchDao.listByWorkitem(7L, workitemId);
        assertEquals(3, rows.size());
        rows.forEach(row -> assertWorkitemSource(row.getSourceType()));
        assertTrue(rows.stream().noneMatch(row -> row.getId().equals(poison.dispatchId())));
        assertEquals(3, dispatchDao.listByTenant(7L, null, null, workitemId,
                null, 20, 0).size());
        assertEquals(3, dispatchDao.countByTenant(7L, null, null, workitemId, null));
        assertEquals(3, runtimeEvents.listByWorkitem(7L, workitemId).size());
        assertEquals(1, runtimeEvents.listByDispatch(7L, first.getId()).size());
        assertNotNull(runtimeEvents.findLatestByDispatchAndType(7L, first.getId(), "agent.progress"));
        assertTrue(runtimeEvents.listByWorkitem(7L, workitemId).stream()
                .noneMatch(row -> row.getId().equals(poison.runtimeEventId())));
        return dispatchDao.findById(third.getId());
    }

    private void complete(DispatchService service, DispatchDao dao, DispatchDO dispatch) {
        assertEquals(1, dao.updateStatus(dispatch.getId(), 7L, DispatchStatus.DISPATCHED,
                null, 501L, "mem://package", null, null, dispatch.getVersion(), 0L));
        service.onAck(7L, dispatch.getId());
        JSONObject progress = new JSONObject();
        progress.put("eventType", "agent.progress");
        progress.put("eventId", "progress-" + dispatch.getId());
        progress.put("seq", 1L);
        progress.put("message", "working");
        service.onProgress(7L, dispatch.getId(), progress);
        assertTrue(service.onResult(7L, 501L, dispatch.getId(), true, "done", null, false, true));
        assertEquals(DispatchStatus.SUCCEEDED, dao.findById(dispatch.getId()).getStatus());
    }

    private void exerciseAggregateQueries(WorkitemDao workitems, long workitemId, DispatchDO latest,
                                          Tenant8Poison poison)
            throws Exception {
        assertEquals(1, workitems.list(7L, null, null, null, null, null,
                true, null, 7L, null, null, null, 0, 20).size());
        assertEquals(1, workitems.count(7L, null, null, null, null, null,
                true, null, 7L, null, null, null));

        DashboardDao dashboard = mapper(DashboardDao.class);
        assertEquals(0, dashboard.countRunningDispatches(7L));
        assertEquals(1, dashboard.countTodayCompletedTasks(7L));
        assertEquals(1, dashboard.countWeekCompletedTasks(7L));
        assertTrue(dashboard.avgTodayCompletedTaskDurationMinutes(7L) >= 0);
        assertEquals(1, dashboard.countInProgressWorkitems(7L));
        assertEquals(0, dashboard.countQueuedDispatches(7L));
        assertEquals(2, dashboard.countOnlineAgents(7L));
        assertEquals(0, dashboard.countActiveSquads(7L));
        List<Map<String, Object>> lifecycle = dashboard.countWorkitemsByLifecycle(7L);
        assertEquals(1, lifecycle.size());
        assertEquals("IN_PROGRESS", mapValue(lifecycle.get(0), "category"));
        assertEquals(1L, numberValue(lifecycle.get(0), "cnt"));
        List<Map<String, Object>> workTypes = dashboard.countWorkitemsByType(7L);
        assertEquals(1, workTypes.size());
        assertEquals("TASK", mapValue(workTypes.get(0), "workType"));
        assertEquals(1L, numberValue(workTypes.get(0), "cnt"));
        List<Map<String, Object>> squadLines = dashboard.squadLineAggregates(7L);
        assertEquals(1, squadLines.size());
        assertEquals(50L, numberValue(squadLines.get(0), "squadId"));
        assertEquals(2L, numberValue(squadLines.get(0), "members"));
        assertEquals(0L, numberValue(squadLines.get(0), "runningTasks"));
        List<Map<String, Object>> squadProgress = dashboard.squadInProgressWorkitems(7L);
        assertEquals(1, squadProgress.size());
        assertEquals(50L, numberValue(squadProgress.get(0), "squadId"));
        assertEquals(1L, numberValue(squadProgress.get(0), "cnt"));
        List<Map<String, Object>> workstations = dashboard.onlineWorkstations(7L);
        assertEquals(2, workstations.size());
        assertTrue(workstations.stream().allMatch(row -> numberValue(row, "agentId") == 40L
                || numberValue(row, "agentId") == 41L));
        assertEquals(3, dashboard.countTodaySucceeded(7L));
        assertEquals(0, dashboard.countTodayFailedOrTimeout(7L));
        assertEquals(2, dashboard.countTodayRetries(7L));
        assertTrue(dashboard.avgTodaySuccessDurationMinutes(7L) >= 0);
        assertTrue(dashboard.listRunningFeed(7L, 20).isEmpty());
        var recent = dashboard.listRecentFeed(7L, 20);
        assertEquals(3, recent.size());
        assertTrue(recent.stream().allMatch(row -> "legacy-updated".equals(row.getWorkitemTitle())
                && !Long.valueOf(poison.dispatchId()).equals(row.getDispatchId())));
        var todayCompleted = dashboard.listTodayCompletedWorkitems(7L);
        assertEquals(1, todayCompleted.size());
        assertEquals(workitemId, todayCompleted.get(0).getWorkitemId());
        var weekCompleted = dashboard.listWeekCompletedWorkitems(7L);
        assertEquals(1, weekCompleted.size());
        assertEquals(workitemId, weekCompleted.get(0).getWorkitemId());
        assertTrue(dashboard.listRunningWorkitems(7L).isEmpty());
        assertTrue(dashboard.listAgentRunning(7L, 40L).isEmpty());
        assertEquals(1, dashboard.agentExists(7L, 40L));
        assertEquals(0, dashboard.agentExists(7L, 80L));

        try (Connection connection = fixture.open("pre_v037_workitems"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE dispatch SET gmt_modified=DATE_SUB(NOW(), INTERVAL 2 HOUR), status='RUNNING' WHERE id=" + latest.getId());
        }
        List<DispatchDO> stuck = mapper(DispatchDao.class).listStuck(
                List.of(DispatchStatus.ACKED, DispatchStatus.RUNNING), System.currentTimeMillis(), 200);
        assertTrue(stuck.stream().anyMatch(row -> row.getId().equals(latest.getId())));
        stuck.forEach(row -> assertWorkitemSource(row.getSourceType()));
    }

    private void seedDashboardReferences() throws Exception {
        try (Connection connection = fixture.open("pre_v037_workitems"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO status_template(id,tenant_id,work_type,name,is_default) VALUES (100,7,'TASK','default',1)");
            statement.executeUpdate("INSERT INTO status_node(id,tenant_id,template_id,code,name,category,sort) VALUES (200,7,100,'developing','Developing','IN_PROGRESS',1)");
            statement.executeUpdate("INSERT INTO agent(id,tenant_id,name,status,is_deleted,version) VALUES (40,7,'worker-a','ONLINE',0,0),(41,7,'worker-b','ONLINE',0,0)");
            statement.executeUpdate("INSERT INTO squad(id,tenant_id,name,status,is_deleted,version) VALUES (50,7,'delivery',0,0,0)");
            statement.executeUpdate("INSERT INTO squad_member(tenant_id,squad_id,agent_id) VALUES (7,50,40),(7,50,41)");
        }
    }

    private Tenant8Poison seedTenant8Poison(long tenant7WorkitemId) throws Exception {
        long poisonId = 80_000L;
        try (Connection connection = fixture.open("pre_v037_workitems"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO status_template(id,tenant_id,work_type,name,is_default) VALUES (800,8,'TASK','tenant8-default',1)");
            statement.executeUpdate("INSERT INTO status_node(id,tenant_id,template_id,code,name,category,sort) VALUES (801,8,800,'developing','Tenant8 Developing','IN_PROGRESS',1)");
            statement.executeUpdate("INSERT INTO workitem(id,tenant_id,work_type,title,content_md,template_id,status_node_id,assignee_type,assignee_ref,priority,creator_id,is_deleted,version) VALUES ("
                    + poisonId + ",8,'TASK','tenant8-poison','must remain unchanged',800,801,'HUMAN',8,3,8,0,0)");
            statement.executeUpdate("INSERT INTO agent(id,tenant_id,name,status,is_deleted,version) VALUES (80,8,'tenant8-worker','ONLINE',0,0)");
            statement.executeUpdate("INSERT INTO squad(id,tenant_id,name,status,is_deleted,version) VALUES (80,8,'tenant8-squad',0,0,0)");
            statement.executeUpdate("INSERT INTO squad_member(tenant_id,squad_id,agent_id) VALUES (8,80,40),(8,50,80)");
            statement.executeUpdate("INSERT INTO dispatch(id,tenant_id,workitem_id,sdlc_step_id,agent_id,executor_id,status,attempt,idempotency_key,creator_id,modifier_id,is_deleted,version) VALUES ("
                    + poisonId + ",8," + tenant7WorkitemId + ",300,40,8501,'DISPATCHED',9,'tenant8-poison',8,8,0,0)");
            statement.executeUpdate("INSERT INTO dispatch(id,tenant_id,workitem_id,sdlc_step_id,agent_id,executor_id,status,attempt,idempotency_key,creator_id,modifier_id,is_deleted,version) VALUES ("
                    + (poisonId + 10) + ",8," + tenant7WorkitemId + ",301,40,8501,'RUNNING',10,'tenant8-running-poison',8,8,0,0)");
            statement.executeUpdate("INSERT INTO artifact(id,tenant_id,workitem_id,dispatch_id,name,type,oss_ref,size) VALUES ("
                    + poisonId + ",8," + tenant7WorkitemId + "," + poisonId
                    + ",'tenant8-poison.md','REPORT','tenant8/poison',8)");
            statement.executeUpdate("INSERT INTO workitem_comment(id,tenant_id,workitem_id,author_type,author_ref,content_md) VALUES ("
                    + poisonId + ",8," + tenant7WorkitemId + ",'HUMAN',8,'tenant8-comment')");
            statement.executeUpdate("INSERT INTO workitem_comment_mention(id,tenant_id,workitem_id,comment_id,target_type,target_ref,display_name_snapshot) VALUES ("
                    + poisonId + ",8," + tenant7WorkitemId + "," + poisonId
                    + ",'AGENT',40,'tenant8-mention')");
            statement.executeUpdate("INSERT INTO workitem_comment_delivery(id,tenant_id,workitem_id,comment_id,target_agent_id,dispatch_id,executor_id,status,error) VALUES ("
                    + poisonId + ",8," + tenant7WorkitemId + "," + poisonId + ",40," + poisonId
                    + ",8501,'DELIVERED',NULL)");
            statement.executeUpdate("INSERT INTO dispatch_runtime_event(id,tenant_id,workitem_id,dispatch_id,agent_id,event_id,seq,event_type,message,detail_json) VALUES ("
                    + poisonId + ",8," + tenant7WorkitemId + "," + poisonId
                    + ",40,'tenant8-event',1,'agent.progress','tenant8-runtime',JSON_OBJECT('tenant',8))");
        }
        return new Tenant8Poison(poisonId, poisonId, poisonId + 10, poisonId, poisonId,
                poisonId, poisonId, poisonId);
    }

    private void assertWorkspace8PoisonUnchanged(Tenant8Poison poison) {
        WorkitemDO workitem = mapper(WorkitemDao.class).findById(poison.workitemId());
        assertEquals(8L, workitem.getTenantId());
        assertEquals("tenant8-poison", workitem.getTitle());
        assertEquals(0, workitem.getVersion());
        DispatchDO dispatch = mapper(DispatchDao.class).findById(poison.dispatchId());
        assertEquals(8L, dispatch.getTenantId());
        assertEquals(DispatchStatus.DISPATCHED, dispatch.getStatus());
        assertEquals(0, dispatch.getVersion());
        DispatchDO runningDispatch = mapper(DispatchDao.class).findById(poison.runningDispatchId());
        assertEquals(8L, runningDispatch.getTenantId());
        assertEquals(DispatchStatus.RUNNING, runningDispatch.getStatus());
        assertEquals(0, runningDispatch.getVersion());
        ArtifactDO artifact = mapper(ArtifactDao.class).findById(poison.artifactId());
        assertEquals(8L, artifact.getTenantId());
        assertEquals("tenant8-poison.md", artifact.getName());
        WorkitemCommentDO comment = mapper(WorkitemCommentDao.class).findById(8L, poison.commentId());
        assertEquals("tenant8-comment", comment.getContentMd());
        assertEquals(1, mapper(WorkitemCommentMentionDao.class)
                .listByWorkitem(8L, dispatch.getWorkitemId()).size());
        GuidanceDO guidance = mapper(GuidanceDao.class).findById(poison.guidanceId());
        assertEquals("DELIVERED", guidance.getStatus());
        assertNull(guidance.getReplyCommentId());
        assertEquals("tenant8-runtime", mapper(DispatchRuntimeEventDao.class)
                .listByDispatch(8L, poison.dispatchId()).get(0).getMessage());
    }

    private static Object mapValue(Map<String, Object> row, String key) {
        return row.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static long numberValue(Map<String, Object> row, String key) {
        return ((Number) mapValue(row, key)).longValue();
    }

    private WorkitemDO workitem() {
        WorkitemDO row = new WorkitemDO();
        row.setTenantId(7L);
        row.setWorkType("TASK");
        row.setTitle("legacy");
        row.setContentMd("pre-v037");
        row.setTemplateId(100L);
        row.setStatusNodeId(200L);
        row.setAssigneeType("HUMAN");
        row.setAssigneeRef(7L);
        row.setPriority(2);
        row.setCreatorId(7L);
        return row;
    }

    private WorkitemCommentDO comment(long workitemId, String authorType, long authorRef, String content) {
        WorkitemCommentDO row = new WorkitemCommentDO();
        row.setTenantId(7L);
        row.setSourceType("WORKITEM");
        row.setWorkitemId(workitemId);
        row.setAuthorType(authorType);
        row.setAuthorRef(authorRef);
        row.setContentMd(content);
        return row;
    }

    private void assertWorkitemSource(String sourceType) {
        assertEquals("WORKITEM", sourceType);
    }

    private <T> T mapper(Class<T> type) {
        return session.getMapper(type);
    }

    private record Tenant8Poison(long workitemId, long dispatchId, long runningDispatchId,
                                 long artifactId,
                                 long commentId, long mentionId, long guidanceId,
                                 long runtimeEventId) {
    }

    @Intercepts({
            @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @Signature(type = Executor.class, method = "query", args = {
                    MappedStatement.class, Object.class, org.apache.ibatis.session.RowBounds.class,
                    org.apache.ibatis.session.ResultHandler.class})
    })
    static final class SqlFailureRecorder implements Interceptor {
        private final List<String> failures = new ArrayList<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            try {
                return invocation.proceed();
            } catch (Throwable failure) {
                failures.add(rootMessage(failure));
                throw failure;
            }
        }

        @Override
        public Object plugin(Object target) {
            return Plugin.wrap(target, this);
        }

        @Override
        public void setProperties(Properties properties) {
        }

        private static String rootMessage(Throwable failure) {
            Throwable cursor = failure;
            while (cursor.getCause() != null) {
                cursor = cursor.getCause();
            }
            return String.valueOf(cursor.getMessage());
        }
    }
}
