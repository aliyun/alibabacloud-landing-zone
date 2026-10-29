package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.taskpackage.TaskPackager;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.workitem.WorkitemControlEventWriter;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemEventDO;
import com.aliyun.autowonder.workitem.WorkitemEventDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real MySQL checks for the control-event contract. The DAO is a thin JDBC adapter so the
 * state transitions, pointer writes and event inserts run through the same database transaction.
 */
@Testcontainers(disabledWithoutDocker = true)
class DispatchControlEventMySqlTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("control_events").withUsername("test").withPassword("test")
            .withCommand("--log-bin-trust-function-creators=1");

    private DriverManagerDataSource dataSource;
    private DataSourceTransactionManager txManager;
    private JdbcTemplate jdbc;
    private DispatchDao dispatchDao;
    private WorkitemDao workitemDao;
    private WorkitemEventDao eventDao;
    private DispatchRecoveryService recovery;
    private DispatchPauseService pause;
    private DispatchCheckpointService checkpointService;
    private WorkitemControlEventWriter writer;
    private AgentDao agentDao;
    private UserDao userDao;

    @BeforeEach
    void setUp() throws Exception {
        resetSchema();
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        txManager = new DataSourceTransactionManager(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        dispatchDao = mock(DispatchDao.class);
        workitemDao = mock(WorkitemDao.class);
        eventDao = mock(WorkitemEventDao.class);

        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(10L);
        workitem.setTenantId(1L);
        when(workitemDao.findById(10L)).thenReturn(workitem);
        when(workitemDao.findByIdForUpdate(10L, 1L)).thenReturn(workitem);

        wireDispatchDao();
        wireEventDao();
        agentDao = mock(AgentDao.class);
        userDao = mock(UserDao.class);
        when(agentDao.findById(20L)).thenReturn(agentNamed("RD Snapshot"));
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(userNamed("过载"));
        writer = new WorkitemControlEventWriter(eventDao);
        writer.setNameResolvers(agentDao, userDao);
        recovery = new DispatchRecoveryService(jdbc, txManager,
                dispatchDao, workitemDao, mock(DispatchControlTransport.class));
        recovery.setControlEvents(writer);
        checkpointService = mock(DispatchCheckpointService.class);
        pause = new DispatchPauseService(dispatchDao, checkpointService,
                mock(DispatchControlTransport.class));
        pause.setRecovery(recovery);
        pause.setTransactionManager(txManager);
    }

    @Test
    void assignmentCreationRecordsOneControlEventWithRequesterAndExactDispatch() {
        DispatchService service = continueService(mock(RedisManager.class));
        DispatchDO created = service.enqueueAssignment(1L, 10L, 30L, 20L, 1, 7L);
        DispatchDO duplicate = service.enqueueAssignment(1L, 10L, 30L, 20L, 1, 7L);

        assertEquals(created.getId(), duplicate.getId());
        assertEquals("PENDING", dispatchStatus(created.getId()));
        var events = controlEvents(created.getId());
        assertEquals(1, events.size());
        JSONObject start = events.get(0);
        assertEquals("START", start.getString("action"));
        assertEquals("CONFIRMED", start.getString("phase"));
        assertEquals("ASSIGNMENT", start.getString("origin"));
        assertEquals(created.getId(), start.getLong("dispatchId"));
        assertEquals(7L, start.getLong("requestedById"));
        assertEquals("过载", start.getString("requestedByName"));
        assertEquals("RD Snapshot", start.getString("agentName"));
        assertEquals(0, recovery.controlEventId(1L, created.getId()), "creation must not replace a stop-intent pointer");
    }

    @Test
    void assignmentControlPreservesAgentActorAndDoesNotUseTokenOwnerAsRequester() {
        DispatchService service = continueService(mock(RedisManager.class));
        DispatchDO created = service.enqueueAssignment(1L, 10L, 30L, 20L, 1, 7L,
                com.aliyun.autowonder.workitem.AssignmentActor.agent(21L, "PD 原名"));

        JSONObject start = controlEvents(created.getId()).get(0);
        assertEquals("PD 原名", start.getString("requestedByName"));
        assertEquals(null, start.getLong("requestedById"));
        assertEquals("AGENT", jdbc.queryForObject("SELECT actor_type FROM workitem_event", String.class));
        assertEquals(21L, jdbc.queryForObject("SELECT actor_ref FROM workitem_event", Long.class));
    }

    @Test
    void assignmentControlInsertFailureRollsBackDispatchAndCanBeRetried() {
        DispatchService service = continueService(mock(RedisManager.class));
        jdbc.execute("ALTER TABLE workitem_event ADD CONSTRAINT chk_no_start "
                + "CHECK (detail_json IS NULL OR detail_json NOT LIKE '%START%')");

        assertThrows(RuntimeException.class,
                () -> service.enqueueAssignment(1L, 10L, 30L, 20L, 1, 7L));
        assertEquals(0, count("SELECT COUNT(*) FROM dispatch"));
        assertEquals(0, count("SELECT COUNT(*) FROM workitem_event"));

        jdbc.execute("ALTER TABLE workitem_event DROP CHECK chk_no_start");
        DispatchDO created = service.enqueueAssignment(1L, 10L, 30L, 20L, 1, 7L);
        assertEquals(1, controlEvents(created.getId()).size());
    }

    @Test
    void pauseFailureThenCompletionKeepsOriginalRequesterAndRequestAssociation() {
        insertDispatch(100L, "RUNNING", 50L, 0);
        pause.requestPause(1L, 10L, 100L, 7L);
        pause.onPauseFailed(1L, 50L, 100L, "executor pause failed");
        when(agentDao.findById(20L)).thenReturn(agentNamed("Renamed Agent"));
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(userNamed("Renamed User"));
        when(checkpointService.confirmPauseReceipt(1L, 50L, 100L, 1L, "sha")).thenReturn(true);
        assertEquals(DispatchPauseService.CompletionDisposition.PAUSED,
                pause.onCompletedWhilePausing(1L, 50L, 100L, 1L, "sha"));

        assertEquals("PAUSED", dispatchStatus(100L));
        List<JSONObject> events = controlEvents(100L);
        assertEquals(3, events.size());
        assertEquals("REQUESTED", events.get(0).getString("phase"));
        assertEquals("FAILED", events.get(1).getString("phase"));
        assertEquals("CONFIRMED", events.get(2).getString("phase"));
        for (JSONObject event : events) {
            assertEquals("PAUSE", event.getString("action"));
            assertEquals(7L, event.getLong("requestedById"));
            assertEquals("过载", event.getString("requestedByName"));
            assertEquals("RD Snapshot", event.getString("agentName"));
        }
        long pointer = jdbc.queryForObject(
                "SELECT control_event_id FROM dispatch_recovery WHERE tenant_id=1 AND dispatch_id=100", Long.class);
        JSONObject request = eventById(pointer);
        assertEquals(7L, request.getLong("requestedById"));
        assertEquals("REQUESTED", request.getString("phase"));
    }

    @Test
    void continueEventFailureRollsBackOldPausedStatusAndNewDispatch() throws Exception {
        insertDispatch(100L, "PAUSED", 50L, 0);
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE workitem_event ADD CONSTRAINT chk_no_resume CHECK (detail_json IS NULL OR detail_json NOT LIKE '%RESUME%')");
        }
        DispatchService service = continueService(redisContinueOnly());

        assertThrows(RuntimeException.class, () -> service.continueDispatch(1L, 10L, 100L, 7L));

        assertEquals("PAUSED", dispatchStatus(100L));
        assertEquals(1, count("SELECT COUNT(*) FROM dispatch WHERE tenant_id=1 AND workitem_id=10"));
        assertEquals(0, count("SELECT COUNT(*) FROM workitem_event"));
    }

    @Test
    void terminalRetryWaitsForConcurrentCloseAndDoesNotInsertPending() throws Exception {
        insertDispatch(100L, "FAILED", 50L, 0);
        DispatchService service = continueService(redisContinueOnly());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = open()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT closed FROM workitem_execution_control WHERE tenant_id=1 AND workitem_id=10 FOR UPDATE")) {
                lock.executeQuery().close();
            }
            Future<?> retry = pool.submit(() -> service.continueDispatch(1L, 10L, 100L, 7L));
            // Let the retry pass any pre-lock read and block on the workitem control row.
            Thread.sleep(300);
            try (PreparedStatement close = holder.prepareStatement(
                    "UPDATE workitem_execution_control SET closed=1 WHERE tenant_id=1 AND workitem_id=10")) {
                close.executeUpdate();
            }
            holder.commit();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> retry.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof com.aliyun.autowonder.common.error.BizException,
                    String.valueOf(failure.getCause()));
        } finally {
            pool.shutdownNow();
        }
        assertEquals("FAILED", dispatchStatus(100L));
        assertEquals(1, count("SELECT COUNT(*) FROM dispatch WHERE tenant_id=1 AND workitem_id=10"));
        assertEquals(0, count("SELECT COUNT(*) FROM workitem_event WHERE event_type='EXECUTION_CONTROL'"));
    }

    @Test
    void continueDuplicateKeyRollsBackOldStateAndKeepsOriginalFailure() throws Exception {
        insertDispatch(100L, "PAUSED", 50L, 0);
        jdbc.update("INSERT INTO dispatch(id,tenant_id,workitem_id,sdlc_step_id,agent_id,executor_id,status,attempt,"
                        + "idempotency_key,version,source_type) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                99L, 1L, 10L, 77L, 20L, null, "SUCCEEDED", 1, "collision", 0, "WORKITEM");
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER force_dispatch_duplicate BEFORE INSERT ON dispatch "
                    + "FOR EACH ROW SET NEW.idempotency_key='collision'");
        }
        DispatchService service = continueService(redisContinueOnly());

        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> service.continueDispatch(1L, 10L, 100L, 7L));

        assertEquals("PAUSED", dispatchStatus(100L));
        assertEquals(2, count("SELECT COUNT(*) FROM dispatch WHERE tenant_id=1 AND workitem_id=10"));
        assertEquals(0, count("SELECT COUNT(*) FROM workitem_event WHERE event_type='EXECUTION_CONTROL'"));
    }

    @Test
    void continueFromPausedWritesResumeMode() {
        insertDispatch(100L, "PAUSED", 50L, 0);
        DispatchService service = continueService(redisContinueOnly());
        DispatchDO created = service.continueDispatch(1L, 10L, 100L, 7L);
        assertNotNull(created);
        JSONObject event = controlEvents(100L).get(controlEvents(100L).size() - 1);
        assertEquals("RESUME", event.getString("action"));
        assertEquals("RESUME", event.getString("mode"));
        assertEquals(100L, event.getLong("sourceDispatchId"));
        assertEquals(created.getId(), event.getLong("targetDispatchId"));
    }

    @Test
    void continueFromFailedWritesRetryMode() {
        insertDispatch(100L, "FAILED", 50L, 0);
        DispatchService service = continueService(redisContinueOnly());
        DispatchDO created = service.continueDispatch(1L, 10L, 100L, 7L);
        assertNotNull(created);
        JSONObject event = controlEvents(100L).get(controlEvents(100L).size() - 1);
        assertEquals("RESUME", event.getString("action"));
        assertEquals("RETRY", event.getString("mode"));
        assertEquals(100L, event.getLong("sourceDispatchId"));
        assertEquals(created.getId(), event.getLong("targetDispatchId"));
    }

    @Test
    void timeoutStopKeepsTimeoutAndUsesSystemOrigin() {
        insertDispatch(100L, "DISPATCHED", 50L, 0);
        DispatchDO current = dispatchDao.findById(100L);
        assertEquals(1, recovery.transition(current, DispatchStatus.TIMEOUT, null, null, null, null, "ACK_TIMEOUT"));
        assertEquals(1, count("SELECT COUNT(*) FROM dispatch_recovery WHERE dispatch_id=100 AND stop_pending=1"));

        List<JSONObject> requested = controlEvents(100L);
        assertEquals(1, requested.size());
        assertEquals("STOP", requested.get(0).getString("action"));
        assertEquals("TIMEOUT", requested.get(0).getString("origin"));
        assertEquals(0L, requested.get(0).getLong("requestedById"));

        assertTrue(recovery.onStopped(1L, 50L, 100L));
        assertEquals("TIMEOUT", dispatchStatus(100L));
        assertEquals(0, count("SELECT COUNT(*) FROM dispatch_recovery WHERE dispatch_id=100 AND stop_pending=1"));
        List<JSONObject> all = controlEvents(100L);
        assertEquals(2, all.size());
        assertEquals("CONFIRMED", all.get(1).getString("phase"));
        assertEquals("TIMEOUT", all.get(1).getString("origin"));
        assertEquals("STOP", all.get(1).getString("action"));
    }

    @Test
    void closeRecordsDeliveryEventAndStopConfirmationReferencesIt() {
        insertDispatch(100L, "RUNNING", 50L, 0);
        recovery.close(1L, 10L, 7L, false);
        assertEquals("PAUSING", dispatchStatus(100L));
        Long deliveryEvent = jdbc.queryForObject(
                "SELECT id FROM workitem_event WHERE event_type='DELIVERY_CONTROL' AND workitem_id=10", Long.class);
        assertEquals(deliveryEvent, pointer(100L));
        JSONObject close = JSONObject.parseObject(jdbc.queryForObject(
                "SELECT detail_json FROM workitem_event WHERE id=?", String.class, deliveryEvent));
        assertEquals("CLOSE", close.getString("action"));
        assertEquals(7L, close.getLong("requestedById"));
        assertEquals("过载", close.getString("requestedByName"));
        assertEquals("DELIVERY_CLOSE", close.getString("origin"));

        assertTrue(recovery.onStopped(1L, 50L, 100L));
        assertEquals("CANCELED", dispatchStatus(100L));
        JSONObject confirmation = controlEvents(100L).get(controlEvents(100L).size() - 1);
        assertEquals("STOP", confirmation.getString("action"));
        assertEquals("CONFIRMED", confirmation.getString("phase"));
        assertEquals("DELIVERY_CLOSE", confirmation.getString("origin"));
        assertEquals(deliveryEvent, confirmation.getLong("requestEventId"));
        assertEquals(7L, confirmation.getLong("requestedById"));

        recovery.reopen(1L, 10L, 7L);
        assertEquals(1, count("SELECT COUNT(*) FROM workitem_event WHERE event_type='DELIVERY_CONTROL' "
                + "AND detail_json LIKE '%\"action\":\"REOPEN\"%'"));
    }

    @Test
    void endingNormalPausingSupersedesPauseRequestAndCancels() {
        insertDispatch(100L, "RUNNING", 50L, 0);
        pause.requestPause(1L, 10L, 100L, 7L);
        long pauseEvent = pointer(100L);

        recovery.cancel(1L, 10L, 100L, 7L, false);
        assertEquals("PAUSING", dispatchStatus(100L));
        List<JSONObject> afterEnd = controlEvents(100L);
        JSONObject cancel = afterEnd.get(afterEnd.size() - 1);
        assertEquals("CANCEL", cancel.getString("action"));
        assertEquals("REQUESTED", cancel.getString("phase"));
        assertEquals(pauseEvent, cancel.getLong("supersedesRequestEventId"));
        assertTrue(pointer(100L) > pauseEvent);

        assertTrue(recovery.onStopped(1L, 50L, 100L));
        assertEquals("CANCELED", dispatchStatus(100L));
        JSONObject confirmed = controlEvents(100L).get(controlEvents(100L).size() - 1);
        assertEquals("CANCEL", confirmed.getString("action"));
        assertEquals("CONFIRMED", confirmed.getString("phase"));
        assertEquals(7L, confirmed.getLong("requestedById"));
    }

    private AgentDO agentNamed(String name) {
        AgentDO agent = new AgentDO();
        agent.setId(20L);
        agent.setName(name);
        return agent;
    }

    private UserDO userNamed(String name) {
        UserDO user = new UserDO();
        user.setId(7L);
        user.setNickname(name);
        user.setUsername(name);
        user.setIsDeleted(0);
        return user;
    }

    private void wireDispatchDao() {
        when(dispatchDao.findById(anyLong())).thenAnswer(invocation -> readDispatch(invocation.getArgument(0)));
        when(dispatchDao.listByWorkitem(anyLong(), anyLong())).thenAnswer(invocation -> jdbc.queryForList(
                "SELECT id FROM dispatch WHERE tenant_id=? AND workitem_id=? ORDER BY id",
                Long.class, invocation.getArgument(0), invocation.getArgument(1)).stream()
                .map(this::readDispatch).toList());
        when(dispatchDao.findByIdempotencyKey(anyLong(), anyString())).thenAnswer(invocation -> {
            List<Long> ids = jdbc.queryForList("SELECT id FROM dispatch WHERE tenant_id=? AND idempotency_key=?",
                    Long.class, invocation.getArgument(0), invocation.getArgument(1));
            return ids.isEmpty() ? null : readDispatch(ids.get(0));
        });
        when(dispatchDao.findMaxAttempt(anyLong(), anyLong(), any())).thenReturn(1);
        when(dispatchDao.updateStatus(anyLong(), anyLong(), anyString(), any(), any(), any(), any(), any(), anyInt(), anyLong()))
                .thenAnswer(invocation -> jdbc.update("UPDATE dispatch SET status=?,error=?,version=version+1,modifier_id=? "
                                + "WHERE id=? AND tenant_id=? AND version=?",
                        invocation.getArgument(2), invocation.getArgument(7), invocation.getArgument(9),
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(8)));
        doAnswer(invocation -> {
            DispatchDO d = invocation.getArgument(0);
            var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO dispatch(tenant_id,workitem_id,sdlc_step_id,agent_id,executor_id,status,attempt,"
                                + "idempotency_key,error,resume_from_dispatch_id,resume_mode,version,source_type,"
                                + "creator_id,modifier_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, d.getTenantId());
                statement.setLong(2, d.getWorkitemId());
                statement.setObject(3, d.getSdlcStepId());
                statement.setObject(4, d.getAgentId());
                statement.setObject(5, d.getExecutorId());
                statement.setString(6, d.getStatus());
                statement.setInt(7, d.getAttempt() == null ? 0 : d.getAttempt());
                statement.setString(8, d.getIdempotencyKey());
                statement.setString(9, d.getError());
                statement.setObject(10, d.getResumeFromDispatchId());
                statement.setString(11, d.getResumeMode());
                statement.setInt(12, d.getVersion() == null ? 0 : d.getVersion());
                statement.setString(13, d.getSourceType());
                statement.setObject(14, d.getCreatorId());
                statement.setObject(15, d.getModifierId());
                return statement;
            }, keys);
            d.setId(keys.getKey().longValue());
            return null;
        }).when(dispatchDao).insert(any());
    }

    private void wireEventDao() {
        doAnswer(invocation -> {
            WorkitemEventDO event = invocation.getArgument(0);
            var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO workitem_event(tenant_id,workitem_id,event_type,from_val,to_val,actor_type,actor_ref,detail_json) "
                                + "VALUES (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, event.getTenantId());
                statement.setLong(2, event.getWorkitemId());
                statement.setString(3, event.getEventType());
                statement.setString(4, event.getFromVal());
                statement.setString(5, event.getToVal());
                statement.setString(6, event.getActorType());
                statement.setObject(7, event.getActorRef());
                statement.setString(8, event.getDetailJson());
                return statement;
            }, keys);
            event.setId(keys.getKey().longValue());
            return null;
        }).when(eventDao).insert(any());
        when(eventDao.findById(anyLong(), anyLong())).thenAnswer(invocation -> {
            List<WorkitemEventDO> rows = jdbc.query("SELECT * FROM workitem_event WHERE tenant_id=? AND id=?",
                    (rs, n) -> {
                        WorkitemEventDO event = new WorkitemEventDO();
                        event.setId(rs.getLong("id"));
                        event.setTenantId(rs.getLong("tenant_id"));
                        event.setWorkitemId(rs.getLong("workitem_id"));
                        event.setEventType(rs.getString("event_type"));
                        event.setDetailJson(rs.getString("detail_json"));
                        return event;
                    }, invocation.getArgument(0), invocation.getArgument(1));
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    private DispatchService continueService(RedisManager redis) {
        DispatchService service = new DispatchService(dispatchDao,
                mock(com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao.class),
                workitemDao, mock(com.aliyun.autowonder.agent.AgentDao.class),
                mock(com.aliyun.autowonder.agent.AgentVersionDao.class), mock(ExecutorSelector.class),
                mock(PackageContextAssembler.class), mock(TaskPackager.class), mock(DispatchTransport.class),
                mock(SdlcDriver.class), redis, mock(DispatchCheckpointService.class), mock(AuditLogService.class),
                mock(ExecutorRegistry.class));
        service.setRecoveryService(recovery);
        service.setTransactionManager(txManager);
        return service;
    }

    private RedisManager redisContinueOnly() {
        RedisManager redis = mock(RedisManager.class);
        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenAnswer(invocation ->
                invocation.getArgument(0, String.class).startsWith("dispatch:continue:"));
        return redis;
    }

    private void resetSchema() throws Exception {
        try (Connection connection = open();
             Statement statement = connection.createStatement()) {
            for (String table : List.of("workitem_event", "dispatch_recovery", "workitem_execution_control",
                    "workitem_comment_delivery", "dispatch")) {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
            statement.execute("CREATE TABLE dispatch (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT, workitem_id BIGINT, "
                    + "sdlc_step_id BIGINT, agent_id BIGINT, executor_id BIGINT, status VARCHAR(32), attempt INT, "
                    + "idempotency_key VARCHAR(128), error VARCHAR(512), resume_from_dispatch_id BIGINT, resume_mode VARCHAR(32), "
                    + "version INT DEFAULT 0, source_type VARCHAR(32), creator_id BIGINT, modifier_id BIGINT, "
                    + "gmt_create DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3), gmt_modified DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3), "
                    + "UNIQUE KEY uk_idem (tenant_id,idempotency_key)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE workitem_execution_control (tenant_id BIGINT, workitem_id BIGINT, closed TINYINT DEFAULT 0, "
                    + "modifier_id BIGINT DEFAULT 0, gmt_modified DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3), "
                    + "PRIMARY KEY (tenant_id,workitem_id)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE dispatch_recovery (tenant_id BIGINT, dispatch_id BIGINT, cancel_requested TINYINT DEFAULT 0, "
                    + "stop_pending TINYINT DEFAULT 0, forced TINYINT DEFAULT 0, retry_count INT DEFAULT 0, next_retry_at DATETIME(3), "
                    + "phase VARCHAR(32), reason VARCHAR(512), requested_at DATETIME(3), last_sent_at DATETIME(3), "
                    + "modifier_id BIGINT DEFAULT 0, control_event_id BIGINT DEFAULT 0, "
                    + "gmt_modified DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY (tenant_id,dispatch_id)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE workitem_event (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT, workitem_id BIGINT, "
                    + "event_type VARCHAR(64), from_val VARCHAR(255), to_val VARCHAR(255), actor_type VARCHAR(32), actor_ref BIGINT, "
                    + "detail_json LONGTEXT, gmt_create DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE workitem_comment_delivery (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT, "
                    + "workitem_id BIGINT, comment_id BIGINT, target_agent_id BIGINT, dispatch_id BIGINT, status VARCHAR(16), "
                    + "error VARCHAR(1024), reply_comment_id BIGINT, delivered_at DATETIME(3), applied_at DATETIME(3), "
                    + "gmt_modified DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3), retry_dispatch_id BIGINT DEFAULT 0) ENGINE=InnoDB");
            statement.execute("INSERT INTO workitem_execution_control(tenant_id,workitem_id,closed) VALUES (1,10,0)");
        }
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private void insertDispatch(long id, String status, Long executorId, int version) {
        jdbc.update("INSERT INTO dispatch(id,tenant_id,workitem_id,sdlc_step_id,agent_id,executor_id,status,attempt,"
                        + "idempotency_key,version,source_type) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, 1L, 10L, 77L, 20L, executorId, status, 1, "source:" + id, version, "WORKITEM");
    }

    private DispatchDO readDispatch(long id) {
        List<DispatchDO> rows = jdbc.query("SELECT * FROM dispatch WHERE id=?", (rs, n) -> {
            DispatchDO d = new DispatchDO();
            d.setId(rs.getLong("id"));
            d.setTenantId(rs.getLong("tenant_id"));
            d.setWorkitemId(rs.getLong("workitem_id"));
            d.setSdlcStepId((Long) rs.getObject("sdlc_step_id"));
            d.setAgentId((Long) rs.getObject("agent_id"));
            d.setExecutorId((Long) rs.getObject("executor_id"));
            d.setStatus(rs.getString("status"));
            d.setAttempt(rs.getInt("attempt"));
            d.setIdempotencyKey(rs.getString("idempotency_key"));
            d.setError(rs.getString("error"));
            d.setResumeFromDispatchId((Long) rs.getObject("resume_from_dispatch_id"));
            d.setResumeMode(rs.getString("resume_mode"));
            d.setVersion(rs.getInt("version"));
            d.setSourceType(rs.getString("source_type"));
            d.setCreatorId((Long) rs.getObject("creator_id"));
            d.setModifierId((Long) rs.getObject("modifier_id"));
            return d;
        }, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String dispatchStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM dispatch WHERE id=?", String.class, id);
    }

    private long pointer(long dispatchId) {
        Long value = jdbc.queryForObject("SELECT control_event_id FROM dispatch_recovery WHERE tenant_id=1 AND dispatch_id=?",
                Long.class, dispatchId);
        return value == null ? 0L : value;
    }

    private List<JSONObject> controlEvents(long dispatchId) {
        return jdbc.queryForList("SELECT detail_json FROM workitem_event WHERE event_type='EXECUTION_CONTROL' AND workitem_id=10 ORDER BY id",
                        String.class)
                .stream().map(JSONObject::parseObject).filter(event ->
                        Objects.equals(event.getLong("dispatchId"), dispatchId)).toList();
    }

    private JSONObject eventById(long id) {
        return JSONObject.parseObject(jdbc.queryForObject("SELECT detail_json FROM workitem_event WHERE id=?", String.class, id));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
