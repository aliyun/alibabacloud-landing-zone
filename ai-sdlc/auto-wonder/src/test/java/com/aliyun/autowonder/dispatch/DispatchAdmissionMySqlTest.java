package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real MySQL checks for the dispatch admission linearization point. The DAOs are thin JDBC
 * adapters (same approach as {@link DispatchControlEventMySqlTest}) so the row locks, the
 * PACKAGING -> DISPATCHED CAS and the management-state updates share real transactions.
 *
 * <p>Threads are ordered with latches/barriers; no arbitrary sleep decides a race.
 */
@Testcontainers(disabledWithoutDocker = true)
class DispatchAdmissionMySqlTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("dispatch_admission").withUsername("test").withPassword("test");

    private static final long TENANT = 1L;
    private static final long WORKITEM = 10L;
    private static final long AGENT = 41L;
    private static final long SDLC = 9L;
    private static final long DISPATCH = 500L;
    private static final long EXECUTOR = 7L;
    private static final String OSS = "oss://pkg.zip";

    private DriverManagerDataSource dataSource;
    private DataSourceTransactionManager txManager;
    private JdbcTemplate jdbc;
    private DispatchDao dispatchDao;
    private AgentDao agentDao;
    private SdlcDao sdlcDao;
    private DispatchAdmissionService admission;

    @BeforeEach
    void setUp() throws Exception {
        resetSchema();
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        txManager = new DataSourceTransactionManager(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        dispatchDao = mock(DispatchDao.class);
        agentDao = mock(AgentDao.class);
        sdlcDao = mock(SdlcDao.class);
        wireDispatchDao();
        when(agentDao.findByIdForUpdate(TENANT, AGENT)).thenAnswer(invocation -> jdbc.queryForObject(
                "SELECT * FROM agent WHERE tenant_id=? AND id=? AND is_deleted=0 LIMIT 1 FOR UPDATE",
                (rs, n) -> {
                    AgentDO agent = new AgentDO();
                    agent.setId(rs.getLong("id"));
                    agent.setTenantId(rs.getLong("tenant_id"));
                    agent.setStatus(rs.getString("status"));
                    agent.setVersion(rs.getInt("version"));
                    return agent;
                }, TENANT, AGENT));
        when(sdlcDao.findByIdForUpdate(SDLC, TENANT)).thenAnswer(invocation -> jdbc.queryForObject(
                "SELECT * FROM sdlc WHERE tenant_id=? AND id=? AND is_deleted=0 LIMIT 1 FOR UPDATE",
                (rs, n) -> {
                    SdlcDO sdlc = new SdlcDO();
                    sdlc.setId(rs.getLong("id"));
                    sdlc.setTenantId(rs.getLong("tenant_id"));
                    sdlc.setStatus(rs.getString("status"));
                    return sdlc;
                }, TENANT, SDLC));
        DispatchRecoveryService recovery = new DispatchRecoveryService(jdbc, txManager,
                dispatchDao, mock(WorkitemDao.class), mock(DispatchControlTransport.class));
        admission = new DispatchAdmissionService(dispatchDao, agentDao, sdlcDao, txManager);
        admission.setRecoveryService(recovery);
    }

    @Test
    void admissionWaitsForTheManagementLockAndThenRejects() throws Exception {
        seed("ONLINE", "ENABLED", "PACKAGING", 0);
        CountDownLatch managementLocked = new CountDownLatch(1);
        CountDownLatch admissionStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> management = pool.submit(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM agent WHERE tenant_id=? AND id=? FOR UPDATE",
                        Long.class, TENANT, AGENT);
                jdbc.update("UPDATE agent SET status='OFFLINE', version=version+1 WHERE tenant_id=? AND id=?",
                        TENANT, AGENT);
                managementLocked.countDown();
                await(admissionStarted);
            }));
            assertTrue(managementLocked.await(10, TimeUnit.SECONDS));
            Future<DispatchAdmissionService.Result> admissionResult = pool.submit(() -> {
                admissionStarted.countDown();
                return admission.admit(packaged(0), SDLC, EXECUTOR, OSS);
            });

            DispatchAdmissionService.Result result = admissionResult.get(15, TimeUnit.SECONDS);
            management.get(15, TimeUnit.SECONDS);
            assertEquals(DispatchAdmissionService.Outcome.REJECTED, result.outcome());
            assertEquals(DispatchFailureReason.AGENT_OFFLINE, result.failureReason());
        } finally {
            pool.shutdownNow();
        }
        assertEquals("PACKAGING", dispatchStatus());
        assertEquals("OFFLINE", jdbc.queryForObject(
                "SELECT status FROM agent WHERE tenant_id=? AND id=?", String.class, TENANT, AGENT));
    }

    @Test
    void admissionCommitFirstIsNotRetractedByALaterOffline() {
        seed("ONLINE", "ENABLED", "PACKAGING", 0);

        DispatchAdmissionService.Result result = admission.admit(packaged(0), SDLC, EXECUTOR, OSS);

        assertEquals(DispatchAdmissionService.Outcome.ADMITTED, result.outcome());
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                jdbc.update("UPDATE agent SET status='OFFLINE', version=version+1 WHERE tenant_id=? AND id=?",
                        TENANT, AGENT));
        assertEquals("DISPATCHED", dispatchStatus());
        assertEquals("OFFLINE", jdbc.queryForObject(
                "SELECT status FROM agent WHERE tenant_id=? AND id=?", String.class, TENANT, AGENT));
    }

    @Test
    void twoWorkersRacingTheAdmissionCasAdmitOnlyOne() throws Exception {
        seed("ONLINE", "ENABLED", "PACKAGING", 0);
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<DispatchAdmissionService.Result> call = () -> {
            start.await(10, TimeUnit.SECONDS);
            return admission.admit(packaged(0), SDLC, EXECUTOR, OSS);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DispatchAdmissionService.Result> first = pool.submit(call);
            Future<DispatchAdmissionService.Result> second = pool.submit(call);
            List<DispatchAdmissionService.Result> results = List.of(
                    first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            long admitted = results.stream()
                    .filter(r -> r.outcome() == DispatchAdmissionService.Outcome.ADMITTED).count();
            long lost = results.stream()
                    .filter(r -> r.outcome() == DispatchAdmissionService.Outcome.LOST).count();
            assertEquals(1, admitted);
            assertEquals(1, lost);
        } finally {
            pool.shutdownNow();
        }
        assertEquals("DISPATCHED", dispatchStatus());
        assertEquals(1, jdbc.queryForObject("SELECT version FROM dispatch WHERE id=?", Integer.class, DISPATCH));
    }

    private DispatchDO packaged(int version) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(DISPATCH);
        dispatch.setTenantId(TENANT);
        dispatch.setWorkitemId(WORKITEM);
        dispatch.setSdlcStepId(77L);
        dispatch.setAgentId(AGENT);
        dispatch.setSourceType(ExecutionSourceType.WORKITEM.name());
        dispatch.setStatus(DispatchStatus.PACKAGING);
        dispatch.setVersion(version);
        return dispatch;
    }

    private void seed(String agentStatus, String sdlcStatus, String dispatchStatus, int version) {
        jdbc.update("INSERT INTO agent (id,tenant_id,status,version,is_deleted) VALUES (?,?,?,0,0)",
                AGENT, TENANT, agentStatus);
        jdbc.update("INSERT INTO sdlc (id,tenant_id,status,version,is_deleted) VALUES (?,?,?,0,0)",
                SDLC, TENANT, sdlcStatus);
        jdbc.update("INSERT INTO dispatch (id,tenant_id,workitem_id,sdlc_step_id,agent_id,status,version,source_type,is_deleted) "
                        + "VALUES (?,?,?,?,?,?,?,?,0)",
                DISPATCH, TENANT, WORKITEM, 77L, AGENT, dispatchStatus, version,
                ExecutionSourceType.WORKITEM.name());
        jdbc.update("INSERT INTO workitem_execution_control (tenant_id,workitem_id,closed) VALUES (?,?,0)",
                TENANT, WORKITEM);
    }

    private String dispatchStatus() {
        return jdbc.queryForObject("SELECT status FROM dispatch WHERE id=?", String.class, DISPATCH);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("barrier timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private void wireDispatchDao() {
        when(dispatchDao.findById(anyLong())).thenAnswer(invocation -> jdbc.query(
                "SELECT * FROM dispatch WHERE id=?",
                (rs, n) -> {
                    DispatchDO dispatch = new DispatchDO();
                    dispatch.setId(rs.getLong("id"));
                    dispatch.setTenantId(rs.getLong("tenant_id"));
                    dispatch.setWorkitemId(rs.getLong("workitem_id"));
                    dispatch.setSdlcStepId((Long) rs.getObject("sdlc_step_id"));
                    dispatch.setAgentId((Long) rs.getObject("agent_id"));
                    dispatch.setStatus(rs.getString("status"));
                    dispatch.setSourceType(rs.getString("source_type"));
                    dispatch.setResumeFromDispatchId((Long) rs.getObject("resume_from_dispatch_id"));
                    dispatch.setResumeMode(rs.getString("resume_mode"));
                    dispatch.setError(rs.getString("error"));
                    dispatch.setVersion(rs.getInt("version"));
                    return dispatch;
                }, (Object) invocation.getArgument(0)).stream().findFirst().orElse(null));
        when(dispatchDao.updateStatus(anyLong(), anyLong(), anyString(), any(), any(), any(), any(), any(),
                anyInt(), anyLong())).thenAnswer(invocation -> jdbc.update(
                "UPDATE dispatch SET status=?, agent_version_id=COALESCE(?,agent_version_id), "
                        + "executor_id=COALESCE(?,executor_id), package_oss_ref=COALESCE(?,package_oss_ref), "
                        + "result_summary=COALESCE(?,result_summary), error=COALESCE(?,error), "
                        + "version=version+1, modifier_id=? "
                        + "WHERE id=? AND tenant_id=? AND version=? AND is_deleted=0",
                invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4),
                invocation.getArgument(5), invocation.getArgument(6), invocation.getArgument(7),
                invocation.getArgument(9), invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(8)));
    }

    private void resetSchema() throws Exception {
        try (Connection connection = dataSource == null
                ? java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                : dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String table : List.of("dispatch_recovery", "workitem_execution_control", "dispatch", "sdlc", "agent")) {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
            statement.execute("CREATE TABLE agent (id BIGINT PRIMARY KEY, tenant_id BIGINT, status VARCHAR(32), "
                    + "version INT DEFAULT 0, is_deleted TINYINT DEFAULT 0) ENGINE=InnoDB");
            statement.execute("CREATE TABLE sdlc (id BIGINT PRIMARY KEY, tenant_id BIGINT, status VARCHAR(32), "
                    + "version INT DEFAULT 0, is_deleted TINYINT DEFAULT 0) ENGINE=InnoDB");
            statement.execute("CREATE TABLE dispatch (id BIGINT PRIMARY KEY, tenant_id BIGINT, workitem_id BIGINT, "
                    + "sdlc_step_id BIGINT, agent_id BIGINT, executor_id BIGINT, status VARCHAR(32), attempt INT, "
                    + "idempotency_key VARCHAR(128), error VARCHAR(512), resume_from_dispatch_id BIGINT, "
                    + "resume_mode VARCHAR(32), agent_version_id BIGINT, package_oss_ref VARCHAR(255), "
                    + "result_summary VARCHAR(1024), version INT DEFAULT 0, source_type VARCHAR(32), "
                    + "is_deleted TINYINT DEFAULT 0, creator_id BIGINT, modifier_id BIGINT, "
                    + "gmt_create DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3), "
                    + "gmt_modified DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3), "
                    + "UNIQUE KEY uk_idem (tenant_id,idempotency_key)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE workitem_execution_control (tenant_id BIGINT, workitem_id BIGINT, "
                    + "closed TINYINT DEFAULT 0, modifier_id BIGINT DEFAULT 0, "
                    + "gmt_modified DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3), "
                    + "PRIMARY KEY (tenant_id,workitem_id)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE dispatch_recovery (tenant_id BIGINT, dispatch_id BIGINT, "
                    + "cancel_requested TINYINT DEFAULT 0, stop_pending TINYINT DEFAULT 0, forced TINYINT DEFAULT 0, "
                    + "retry_count INT DEFAULT 0, next_retry_at DATETIME(3), phase VARCHAR(32), reason VARCHAR(512), "
                    + "requested_at DATETIME(3), last_sent_at DATETIME(3), modifier_id BIGINT DEFAULT 0, "
                    + "control_event_id BIGINT DEFAULT 0, "
                    + "gmt_modified DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3), "
                    + "PRIMARY KEY (tenant_id,dispatch_id)) ENGINE=InnoDB");
        }
    }
}