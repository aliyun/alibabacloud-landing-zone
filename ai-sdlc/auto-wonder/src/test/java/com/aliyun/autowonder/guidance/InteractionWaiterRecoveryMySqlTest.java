package com.aliyun.autowonder.guidance;

import com.aliyun.autowonder.dispatch.AgentSdlcResolver;
import com.aliyun.autowonder.dispatch.DispatchControlTransport;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchFailureReason;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.DispatchRecoveryService;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.executor.ExecutorDispatchSnapshot;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.workitem.DeliveryRestartStore;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemService;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real MySQL locks for the pause barrier. A late FAILED result can terminalize a
 * PAUSING predecessor without a pause receipt; the waiting rework must then wait for
 * authoritative executor inventory, not for a status string.
 */
@Testcontainers(disabledWithoutDocker = true)
class InteractionWaiterRecoveryMySqlTest {

    private static final long TENANT = 10001L;
    private static final long WORKITEM = 50L;
    private static final long PREDECESSOR = 11741L;
    private static final long WAITER = 11882L;
    private static final long OLDER_WAITER = 11881L;
    private static final long AGENT = 40013L;
    private static final long EXECUTOR = 10L;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("waiter_recovery").withUsername("test").withPassword("test");

    private Fixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new Fixture();
    }

    @Test
    void releasedPredecessorQueryIncludesLateTerminalResults() throws Exception {
        fixture.insertWaiter(WAITER, "2026-10-13 07:52:20.419");
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(fixture.jdbc.getDataSource());
        Configuration mybatis = new Configuration();
        mybatis.setDatabaseId("autowonder-source-aware");
        mybatis.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(mybatis);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapping/DispatchDao.xml"));

        try (var session = factoryBean.getObject().openSession()) {
            DispatchDao query = session.getMapper(DispatchDao.class);
            List<DispatchDO> rows = query.listReleasedPausePredecessors(EXECUTOR, 0L, 200);
            assertEquals(List.of(PREDECESSOR), rows.stream().map(DispatchDO::getId).toList());
            assertTrue(query.existsWaitingForPredecessor(TENANT, PREDECESSOR));
        }
    }

    @Test
    void releasedPredecessorQuerySupportsIdCursor() throws Exception {
        for (long id = 200001L; id <= 200003L; id++) {
            fixture.insertDispatch(id, DispatchStatus.FAILED, "source:" + id, null);
            fixture.insertWaiter(300000L + (id - 200000L), id, "2026-10-13 07:40:00.000");
        }
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(fixture.jdbc.getDataSource());
        Configuration mybatis = new Configuration();
        mybatis.setDatabaseId("autowonder-source-aware");
        mybatis.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(mybatis);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapping/DispatchDao.xml"));

        try (var session = factoryBean.getObject().openSession()) {
            DispatchDao query = session.getMapper(DispatchDao.class);
            assertEquals(List.of(200001L, 200002L), query.listReleasedPausePredecessors(null, 0L, 2)
                    .stream().map(DispatchDO::getId).toList());
            assertEquals(List.of(200003L), query.listReleasedPausePredecessors(null, 200002L, 2)
                    .stream().map(DispatchDO::getId).toList());
        }
    }

    @Test
    void restartQueryFailureKeepsWaiterUntilRecovery() {
        fixture.insertWaiter(WAITER, "2026-10-13 07:52:20.419");
        fixture.registryReturns(snapshot(true, true, Set.of()));
        fixture.jdbc.execute("RENAME TABLE workitem_delivery_restart TO workitem_delivery_restart_unavailable");
        try {
            assertThrows(RuntimeException.class,
                    () -> fixture.service.reconcileWaitersForPredecessor(TENANT, PREDECESSOR));
            assertEquals(DispatchStatus.WAITING_FOR_PAUSE, fixture.status(WAITER));
            verify(fixture.eventPublisher, never()).publishEvent(any());
        } finally {
            fixture.jdbc.execute("RENAME TABLE workitem_delivery_restart_unavailable TO workitem_delivery_restart");
        }

        fixture.service.reconcileWaitersForPredecessor(TENANT, PREDECESSOR);

        assertEquals(DispatchStatus.PENDING, fixture.status(WAITER));
        verify(fixture.eventPublisher, times(1)).publishEvent(any(GuidanceDispatchQueuedEvent.class));
    }

    @Test
    void globalPaginationReleasesAfterFirstPageBlocked() {
        fixture.seedBlockedPage();

        fixture.service.reconcileReleasedWaiters();

        assertEquals(DispatchStatus.PENDING, fixture.status(300201L));
        assertEquals(DispatchStatus.CANCELED, fixture.status(300001L));
        verify(fixture.eventPublisher, times(1)).publishEvent(any(GuidanceDispatchQueuedEvent.class));
    }

    @Test
    void executorPaginationReleasesAfterFirstPageBlocked() {
        fixture.seedBlockedPage();

        fixture.service.reconcileReleasedWaiters(TENANT, EXECUTOR);

        assertEquals(DispatchStatus.PENDING, fixture.status(300201L));
        assertEquals(DispatchStatus.CANCELED, fixture.status(300001L));
        verify(fixture.eventPublisher, times(1)).publishEvent(any(GuidanceDispatchQueuedEvent.class));
    }

    @Test
    void callbackAndCompensationRaceReleasesTheLatestWaiterExactlyOnce() throws Exception {
        fixture.insertWaiter(WAITER, "2026-10-13 07:52:20.419");
        CountDownLatch lockAttempts = new CountDownLatch(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch allowFirst = new CountDownLatch(1);
        when(fixture.workitemDao.findByIdForUpdate(anyLong(), anyLong())).thenAnswer(invocation -> {
            lockAttempts.countDown();
            return fixture.lockWorkitem();
        });
        AtomicInteger rebinds = new AtomicInteger();
        doAnswer(invocation -> {
            if (rebinds.incrementAndGet() == 1) {
                firstEntered.countDown();
                assertTrue(allowFirst.await(10, TimeUnit.SECONDS));
            }
            return null;
        }).when(fixture.workitemService).rebindForInteractionRework(
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> callback = pool.submit(
                    () -> fixture.service.reconcileWaitersForPredecessor(TENANT, PREDECESSOR));
            assertTrue(firstEntered.await(10, TimeUnit.SECONDS));
            Future<?> compensation = pool.submit(() -> {
                fixture.service.reconcileReleasedWaiters();
                return null;
            });
            assertTrue(lockAttempts.await(10, TimeUnit.SECONDS));
            allowFirst.countDown();
            callback.get(20, TimeUnit.SECONDS);
            compensation.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(DispatchStatus.PENDING, fixture.status(WAITER));
        assertEquals(1, rebinds.get());
        verify(fixture.eventPublisher, times(1)).publishEvent(any(GuidanceDispatchQueuedEvent.class));
    }

    @Test
    void predecessorStillOwnedByExecutorCannotReleaseWaiter() {
        fixture.insertWaiter(WAITER, "2026-10-13 07:52:20.419");
        fixture.registryReturns(snapshot(true, true, Set.of(PREDECESSOR)));

        fixture.service.reconcileWaitersForPredecessor(TENANT, PREDECESSOR);

        assertEquals(DispatchStatus.WAITING_FOR_PAUSE, fixture.status(WAITER));
        verify(fixture.workitemService, never()).rebindForInteractionRework(
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(fixture.eventPublisher, never()).publishEvent(any());
    }

    @Test
    void missingOrIncompleteInventoryCannotReleaseWaiter() {
        fixture.insertWaiter(WAITER, "2026-10-13 07:52:20.419");
        fixture.registryReturns(Optional.empty());
        fixture.service.reconcileWaitersForPredecessor(TENANT, PREDECESSOR);
        assertEquals(DispatchStatus.WAITING_FOR_PAUSE, fixture.status(WAITER));

        fixture.registryReturns(Optional.of(snapshot(true, false, Set.of())));
        fixture.service.reconcileReleasedWaiters();
        assertEquals(DispatchStatus.WAITING_FOR_PAUSE, fixture.status(WAITER));
        verify(fixture.workitemService, never()).rebindForInteractionRework(
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void unconfirmedStopKeepsWaiterBlockedEvenWhenInventoryDroppedIt() {
        fixture.insertWaiter(WAITER, "2026-10-13 07:52:20.419");
        fixture.jdbc.update("INSERT INTO dispatch_recovery(tenant_id,dispatch_id,stop_pending) VALUES (?,?,1)",
                TENANT, PREDECESSOR);
        fixture.registryReturns(snapshot(true, true, Set.of()));

        fixture.service.reconcileWaitersForPredecessor(TENANT, PREDECESSOR);

        assertEquals(DispatchStatus.WAITING_FOR_PAUSE, fixture.status(WAITER));
        verify(fixture.workitemService, never()).rebindForInteractionRework(
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void closedDeliveryCancelsTheWaiterInsteadOfResurrectingIt() {
        fixture.insertWaiter(WAITER, "2026-10-13 07:52:20.419");
        fixture.jdbc.update("UPDATE workitem_execution_control SET closed=1 WHERE tenant_id=? AND workitem_id=?",
                TENANT, WORKITEM);
        fixture.registryReturns(snapshot(true, true, Set.of()));

        fixture.service.reconcileWaitersForPredecessor(TENANT, PREDECESSOR);

        assertEquals(DispatchStatus.CANCELED, fixture.status(WAITER));
        assertEquals(DispatchFailureReason.COMMENT_REWORK, fixture.error(WAITER));
        verify(fixture.workitemService, never()).rebindForInteractionRework(
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(fixture.eventPublisher, never()).publishEvent(any());
    }

    @Test
    void newerDeliveryRestartCancelsTheWaiter() {
        fixture.insertWaiter(WAITER, "2026-01-01 00:00:00.000");
        fixture.jdbc.update("INSERT INTO workitem_delivery_restart(tenant_id,workitem_id,restart_round,status,"
                        + "gmt_create,gmt_modified) VALUES (?,?,1,'STARTING','2026-02-01 00:00:00.000','2026-02-01 00:00:00.000')",
                TENANT, WORKITEM);
        fixture.registryReturns(snapshot(true, true, Set.of()));

        fixture.service.reconcileWaitersForPredecessor(TENANT, PREDECESSOR);

        assertEquals(DispatchStatus.CANCELED, fixture.status(WAITER));
        verify(fixture.eventPublisher, never()).publishEvent(any());
    }

    @Test
    void newerFormalExecutionCancelsTheWaiter() {
        fixture.insertWaiter(WAITER, "2026-10-13 07:52:20.419");
        fixture.insertDispatch(11900L, DispatchStatus.PENDING, "interaction-rework:11882", null);
        fixture.registryReturns(snapshot(true, true, Set.of()));

        fixture.service.reconcileWaitersForPredecessor(TENANT, PREDECESSOR);

        assertEquals(DispatchStatus.CANCELED, fixture.status(WAITER));
        assertEquals(DispatchStatus.PENDING, fixture.status(11900L));
        verify(fixture.eventPublisher, never()).publishEvent(any());
    }

    @Test
    void olderWaiterIsSupersededWhenTheLatestOneStarts() {
        fixture.insertWaiter(OLDER_WAITER, "2026-10-13 07:51:00.000");
        fixture.insertWaiter(WAITER, "2026-10-13 07:52:20.419");
        fixture.registryReturns(snapshot(true, true, Set.of()));

        fixture.service.reconcileWaitersForPredecessor(TENANT, PREDECESSOR);

        assertEquals(DispatchStatus.CANCELED, fixture.status(OLDER_WAITER));
        assertEquals(DispatchStatus.PENDING, fixture.status(WAITER));
        verify(fixture.eventPublisher, times(1)).publishEvent(any(GuidanceDispatchQueuedEvent.class));
    }

    @Test
    void repeatedCallbackAfterReleaseDoesNotScheduleAgain() {
        fixture.insertWaiter(WAITER, "2026-10-13 07:52:20.419");
        fixture.registryReturns(snapshot(true, true, Set.of()));

        fixture.service.reconcileWaitersForPredecessor(TENANT, PREDECESSOR);
        fixture.service.reconcileWaitersForPredecessor(TENANT, PREDECESSOR);

        assertEquals(DispatchStatus.PENDING, fixture.status(WAITER));
        verify(fixture.eventPublisher, times(1)).publishEvent(any(GuidanceDispatchQueuedEvent.class));
    }

    @Test
    void activationFailureRollsBackWithoutChangingTheWaiter() {
        fixture.insertWaiter(WAITER, "2026-10-13 07:52:20.419");
        fixture.registryReturns(snapshot(true, true, Set.of()));
        doThrow(new IllegalStateException("rebind failed"))
                .when(fixture.workitemService).rebindForInteractionRework(
                        anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());

        assertThrows(IllegalStateException.class,
                () -> fixture.service.reconcileWaitersForPredecessor(TENANT, PREDECESSOR));

        assertEquals(DispatchStatus.WAITING_FOR_PAUSE, fixture.status(WAITER));
        assertEquals(0, fixture.version(WAITER));
        verify(fixture.eventPublisher, never()).publishEvent(any());
    }

    private static ExecutorDispatchSnapshot snapshot(boolean authoritative, boolean ready, Set<Long> owned) {
        return new ExecutorDispatchSnapshot("session-1", 10, authoritative, ready,
                owned, owned, Set.of(), Set.of(), null, System.currentTimeMillis());
    }

    private final class Fixture {
        final JdbcTemplate jdbc;
        final DispatchDao dispatchDao = mock(DispatchDao.class);
        final WorkitemDao workitemDao = mock(WorkitemDao.class);
        final WorkitemService workitemService = mock(WorkitemService.class);
        final DispatchService dispatchService = mock(DispatchService.class);
        final AgentSdlcResolver resolver = mock(AgentSdlcResolver.class);
        final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        final ExecutorRegistry registry = mock(ExecutorRegistry.class);
        final DispatchRecoveryService recovery;
        final InteractionWorkflowService service;

        Fixture() throws Exception {
            var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
            jdbc = new JdbcTemplate(dataSource);
            resetSchema();
            wireDispatchDao();
            wireWorkitemDao();
            recovery = new DispatchRecoveryService(jdbc, new DataSourceTransactionManager(dataSource),
                    dispatchDao, workitemDao, mock(DispatchControlTransport.class));
            recovery.setExecutorRegistry(registry);
            recovery.setRestartStore(new DeliveryRestartStore(jdbc));
            when(dispatchService.isInteractionDispatch(any(DispatchDO.class)))
                    .thenAnswer(invocation -> DispatchService.isInteractionDispatchValue(invocation.getArgument(0)));
            when(dispatchService.releaseInteractionRework(anyLong(), anyLong()))
                    .thenAnswer(invocation -> release(invocation.getArgument(0), invocation.getArgument(1)));
            when(dispatchService.cancelWaitingInteractionRework(anyLong(), anyLong()))
                    .thenAnswer(invocation -> cancel(invocation.getArgument(0), invocation.getArgument(1)));
            when(resolver.resolveSdlcId(TENANT, AGENT)).thenReturn(810L);
            SdlcStepDO step = new SdlcStepDO();
            step.setId(812L);
            when(resolver.resolveStep(TENANT, 810L, "812", null)).thenReturn(step);
            when(registry.currentDispatchSnapshot(EXECUTOR)).thenReturn(Optional.of(snapshot(true, true, Set.of())));
            service = new InteractionWorkflowService(dispatchDao, dispatchService,
                    mock(DispatchPauseService.class), resolver, workitemService, eventPublisher,
                    new DataSourceTransactionManager(dataSource), workitemDao);
            service.setRecovery(recovery);
            insertPredecessor();
        }

        void registryReturns(Optional<ExecutorDispatchSnapshot> snapshot) {
            when(registry.currentDispatchSnapshot(EXECUTOR)).thenReturn(snapshot);
        }

        void registryReturns(ExecutorDispatchSnapshot snapshot) {
            registryReturns(Optional.of(snapshot));
        }

        WorkitemDO lockWorkitem() {
            return jdbc.query("SELECT id,tenant_id FROM workitem WHERE id=? AND tenant_id=? FOR UPDATE",
                    (rs, rowNum) -> {
                        WorkitemDO row = new WorkitemDO();
                        row.setId(rs.getLong("id"));
                        row.setTenantId(rs.getLong("tenant_id"));
                        return row;
                    }, WORKITEM, TENANT).stream().findFirst().orElse(null);
        }

        void insertPredecessor() {
            insertDispatch(PREDECESSOR, DispatchStatus.FAILED, "source:11741", "RESULT_STATE_CONFLICT");
        }

        void insertWaiter(long id, String gmtCreate) {
            insertWaiter(id, PREDECESSOR, gmtCreate);
        }

        void insertWaiter(long id, long predecessorId, String gmtCreate) {
            insertDispatch(id, DispatchStatus.WAITING_FOR_PAUSE, "interaction-rework:" + id,
                    null, "waitForDispatchId=" + predecessorId, "COMMENT_REWORK", gmtCreate);
        }

        void seedBlockedPage() {
            for (long i = 1; i <= 200; i++) {
                long predecessor = 200000L + i;
                insertDispatch(predecessor, DispatchStatus.FAILED, "source:" + predecessor,
                        "RESULT_STATE_CONFLICT");
                jdbc.update("INSERT INTO dispatch_recovery(tenant_id,dispatch_id,stop_pending) VALUES (?,?,1)",
                        TENANT, predecessor);
                insertWaiter(300000L + i, predecessor, "2026-10-13 07:40:00.000");
            }
            insertDispatch(200201L, DispatchStatus.PAUSED, "source:200201", null);
            insertWaiter(300201L, 200201L, "2026-10-13 07:40:00.000");
        }

        void insertDispatch(long id, String status, String idempotencyKey, String error) {
            insertDispatch(id, status, idempotencyKey, error, null, null, "2026-10-13 07:40:00.000");
        }

        void insertDispatch(long id, String status, String idempotencyKey, String error,
                String resultSummary, String resumeMode, String gmtCreate) {
            jdbc.update("INSERT INTO dispatch(id,tenant_id,workitem_id,sdlc_step_id,agent_id,executor_id,status,"
                            + "attempt,idempotency_key,error,result_summary,resume_mode,version,source_type,"
                            + "gmt_create,gmt_modified) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,'WORKITEM',?,?)",
                    id, TENANT, WORKITEM, 812L, AGENT, EXECUTOR, status, 1, idempotencyKey, error,
                    resultSummary, resumeMode, Timestamp.valueOf(gmtCreate), Timestamp.valueOf(gmtCreate));
        }

        String status(long id) {
            return jdbc.queryForObject("SELECT status FROM dispatch WHERE id=?", String.class, id);
        }

        String error(long id) {
            return jdbc.queryForObject("SELECT error FROM dispatch WHERE id=?", String.class, id);
        }

        int version(long id) {
            return jdbc.queryForObject("SELECT version FROM dispatch WHERE id=?", Integer.class, id);
        }

        private boolean release(long tenantId, long dispatchId) {
            DispatchDO row = readDispatch(dispatchId);
            return row != null && row.getTenantId() == tenantId
                    && DispatchStatus.WAITING_FOR_PAUSE.equals(row.getStatus())
                    && dispatchDao.updateStatus(dispatchId, tenantId, DispatchStatus.PENDING,
                    null, null, null, null, null, row.getVersion(), 0L) == 1;
        }

        private boolean cancel(long tenantId, long dispatchId) {
            DispatchDO row = readDispatch(dispatchId);
            return row != null && row.getTenantId() == tenantId
                    && DispatchStatus.WAITING_FOR_PAUSE.equals(row.getStatus())
                    && dispatchDao.updateStatus(dispatchId, tenantId, DispatchStatus.CANCELED,
                    null, null, null, null, DispatchFailureReason.COMMENT_REWORK, row.getVersion(), 0L) == 1;
        }

        private void wireDispatchDao() {
            when(dispatchDao.findById(anyLong())).thenAnswer(invocation -> readDispatch(invocation.getArgument(0)));
            when(dispatchDao.listByWorkitem(anyLong(), anyLong())).thenAnswer(invocation -> jdbc.queryForList(
                    "SELECT id FROM dispatch WHERE tenant_id=? AND workitem_id=? ORDER BY id",
                    Long.class, invocation.getArgument(0), invocation.getArgument(1)).stream()
                    .map(this::readDispatch).toList());
            when(dispatchDao.listReleasedPausePredecessors(any(), anyLong(), anyInt())).thenAnswer(invocation -> {
                Long executorId = invocation.getArgument(0);
                Long afterId = invocation.getArgument(1);
                Integer limit = invocation.getArgument(2);
                String filter = executorId == null ? "" : " AND d.executor_id=" + executorId;
                return jdbc.query("SELECT d.id FROM dispatch d WHERE d.is_deleted=0 AND d.id>" + afterId + filter
                                + " AND (d.status IN ('PAUSED','SUCCEEDED','FAILED')"
                                + " OR (d.status='CANCELED' AND d.error LIKE 'PAUSE_CONFIRMATION_MISSING:%'))"
                                + " AND EXISTS (SELECT 1 FROM dispatch waiter WHERE waiter.tenant_id=d.tenant_id"
                                + " AND waiter.workitem_id=d.workitem_id AND waiter.status='WAITING_FOR_PAUSE'"
                                + " AND waiter.result_summary=CONCAT('waitForDispatchId=',d.id) AND waiter.is_deleted=0)"
                                + " ORDER BY d.id LIMIT " + limit,
                        (rs, n) -> rs.getLong(1)).stream().map(this::readDispatch).toList();
            });
            when(dispatchDao.updateStatus(anyLong(), anyLong(), anyString(), any(), any(), any(), any(),
                    any(), anyInt(), anyLong())).thenAnswer(invocation -> jdbc.update(
                    "UPDATE dispatch SET status=?,error=?,version=version+1,gmt_modified=NOW(3) "
                            + "WHERE id=? AND tenant_id=? AND version=?",
                    invocation.getArgument(2), invocation.getArgument(7), invocation.getArgument(0),
                    invocation.getArgument(1), invocation.getArgument(8)));
        }

        private void wireWorkitemDao() {
            when(workitemDao.findByIdForUpdate(anyLong(), anyLong())).thenAnswer(invocation -> lockWorkitem());
        }

        private DispatchDO readDispatch(long id) {
            List<DispatchDO> rows = jdbc.query("SELECT * FROM dispatch WHERE id=?", (rs, n) -> {
                DispatchDO row = new DispatchDO();
                row.setId(rs.getLong("id"));
                row.setTenantId(rs.getLong("tenant_id"));
                row.setWorkitemId(rs.getLong("workitem_id"));
                row.setSdlcStepId((Long) rs.getObject("sdlc_step_id"));
                row.setAgentId((Long) rs.getObject("agent_id"));
                row.setExecutorId((Long) rs.getObject("executor_id"));
                row.setStatus(rs.getString("status"));
                row.setAttempt(rs.getInt("attempt"));
                row.setIdempotencyKey(rs.getString("idempotency_key"));
                row.setError(rs.getString("error"));
                row.setResultSummary(rs.getString("result_summary"));
                row.setResumeFromDispatchId((Long) rs.getObject("resume_from_dispatch_id"));
                row.setResumeMode(rs.getString("resume_mode"));
                row.setVersion(rs.getInt("version"));
                row.setSourceType(rs.getString("source_type"));
                row.setGmtCreate(rs.getTimestamp("gmt_create"));
                row.setGmtModified(rs.getTimestamp("gmt_modified"));
                return row;
            }, id);
            return rows.isEmpty() ? null : rows.get(0);
        }

        private void resetSchema() throws Exception {
            try (Connection connection = DriverManager.getConnection(
                    MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                 Statement statement = connection.createStatement()) {
                for (String table : List.of("workitem_delivery_restart", "dispatch_recovery",
                        "workitem_execution_control", "dispatch", "workitem")) {
                    statement.execute("DROP TABLE IF EXISTS " + table);
                }
                statement.execute("CREATE TABLE workitem (id BIGINT PRIMARY KEY, tenant_id BIGINT) ENGINE=InnoDB");
                statement.execute("CREATE TABLE workitem_execution_control (tenant_id BIGINT, workitem_id BIGINT, "
                        + "closed TINYINT DEFAULT 0, modifier_id BIGINT DEFAULT 0, "
                        + "gmt_modified DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY (tenant_id,workitem_id)) ENGINE=InnoDB");
                statement.execute("CREATE TABLE workitem_delivery_restart (tenant_id BIGINT, workitem_id BIGINT, "
                        + "restart_round INT, restart_token VARCHAR(128), requested_by BIGINT, operator_type VARCHAR(32), "
                        + "scheduled_start_at DATETIME(3), sdlc_step_id BIGINT, agent_id BIGINT, status VARCHAR(32), "
                        + "stop_reason VARCHAR(512), dispatch_id BIGINT, gmt_create DATETIME(3), gmt_modified DATETIME(3), "
                        + "PRIMARY KEY (tenant_id,workitem_id,restart_round)) ENGINE=InnoDB");
                statement.execute("CREATE TABLE dispatch_recovery (tenant_id BIGINT, dispatch_id BIGINT, "
                        + "cancel_requested TINYINT DEFAULT 0, stop_pending TINYINT DEFAULT 0, forced TINYINT DEFAULT 0, "
                        + "retry_count INT DEFAULT 0, next_retry_at DATETIME(3), phase VARCHAR(32), reason VARCHAR(512), "
                        + "requested_at DATETIME(3), last_sent_at DATETIME(3), modifier_id BIGINT DEFAULT 0, "
                        + "control_event_id BIGINT DEFAULT 0, gmt_modified DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3), "
                        + "PRIMARY KEY (tenant_id,dispatch_id)) ENGINE=InnoDB");
                statement.execute("CREATE TABLE dispatch (id BIGINT PRIMARY KEY, tenant_id BIGINT, workitem_id BIGINT, "
                        + "sdlc_step_id BIGINT, agent_id BIGINT, agent_version_id BIGINT, executor_id BIGINT, "
                        + "package_oss_ref VARCHAR(512), status VARCHAR(32), attempt INT, "
                        + "idempotency_key VARCHAR(128), result_summary VARCHAR(255), error VARCHAR(512), "
                        + "resume_from_dispatch_id BIGINT, delivery_source_dispatch_id BIGINT, resume_mode VARCHAR(32), "
                        + "debug_log_enabled TINYINT DEFAULT 0, version INT DEFAULT 0, "
                        + "source_type VARCHAR(32), creator_id BIGINT, modifier_id BIGINT, is_deleted TINYINT DEFAULT 0, "
                        + "gmt_create DATETIME(3), gmt_modified DATETIME(3)) ENGINE=InnoDB");
                statement.execute("INSERT INTO workitem(id,tenant_id) VALUES (" + WORKITEM + "," + TENANT + ")");
                statement.execute("INSERT INTO workitem_execution_control(tenant_id,workitem_id,closed) VALUES ("
                        + TENANT + "," + WORKITEM + ",0)");
            }
        }
    }
}
