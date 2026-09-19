package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchRecoveryServiceTest {
    JdbcTemplate jdbc;
    DispatchDao dao;
    WorkitemDao workitems;
    DispatchControlTransport transport;
    DispatchRecoveryService service;

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE dispatch (id BIGINT PRIMARY KEY, tenant_id BIGINT, workitem_id BIGINT, executor_id BIGINT, status VARCHAR(32),source_type VARCHAR(32),error VARCHAR(512),version INT DEFAULT 0,gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE workitem_comment_delivery (id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,workitem_id BIGINT,comment_id BIGINT,target_agent_id BIGINT,dispatch_id BIGINT,executor_id BIGINT,status VARCHAR(16),error VARCHAR(1024),reply_comment_id BIGINT,delivered_at TIMESTAMP,applied_at TIMESTAMP,gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,retry_dispatch_id BIGINT DEFAULT 0)");
        String schema = Files.readString(Path.of("docs/migration/V059__dispatch_recovery.sql")).split("ALTER TABLE")[0].replaceAll("(?m)^--.*$", "");
        for (String sql : schema.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        dao = mock(DispatchDao.class); workitems = mock(WorkitemDao.class); transport = mock(DispatchControlTransport.class);
        service = new DispatchRecoveryService(jdbc, new DataSourceTransactionManager(ds), dao, workitems, transport);
        var w = new WorkitemDO(); w.setTenantId(1L); w.setId(10L); when(workitems.findById(10L)).thenReturn(w);
        when(dao.findById(anyLong())).thenAnswer(i -> jdbc.query("SELECT * FROM dispatch WHERE id=?", (rs,n) -> {
            var d = new DispatchDO(); d.setId(rs.getLong("id")); d.setTenantId(rs.getLong("tenant_id"));
            d.setWorkitemId(rs.getLong("workitem_id")); d.setSourceType(rs.getString("source_type")); d.setStatus(rs.getString("status")); d.setVersion(rs.getInt("version"));
            d.setExecutorId((Long) rs.getObject("executor_id")); d.setError(rs.getString("error")); d.setAgentId(20L); d.setAttempt(1);
            return d;
        }, (Object) i.getArgument(0)).stream().findFirst().orElse(null));
        when(dao.listByWorkitem(1L,10L)).thenAnswer(i -> jdbc.queryForList("SELECT id FROM dispatch WHERE tenant_id=1 AND workitem_id=10", Long.class).stream().map(dao::findById).toList());
        when(dao.updateStatus(anyLong(),anyLong(),anyString(),any(),any(),any(),any(),any(),anyInt(),anyLong())).thenAnswer(i ->
                jdbc.update("UPDATE dispatch SET status=?,error=?,version=version+1,gmt_modified=NOW() WHERE id=? AND tenant_id=? AND version=?",
                        i.getArgument(2),i.getArgument(7),i.getArgument(0),i.getArgument(1),i.getArgument(8)));
        when(dao.returnPackagingToPending(anyLong(),anyLong(),anyInt(),anyLong())).thenAnswer(i -> jdbc.update("UPDATE dispatch SET status='PENDING',version=version+1 WHERE id=? AND tenant_id=? AND version=? AND status='PACKAGING'",i.getArgument(0),i.getArgument(1),i.getArgument(2)));
        doAnswer(i -> { DispatchDO d = i.getArgument(0); jdbc.update("INSERT INTO dispatch (id,tenant_id,workitem_id,status) VALUES (?,?,?,?)",d.getId(),d.getTenantId(),d.getWorkitemId(),d.getStatus());return null; }).when(dao).insert(any());
    }
    void dispatch(String status, Long executor) { jdbc.update("INSERT INTO dispatch (id,tenant_id,workitem_id,status,executor_id) VALUES (100,1,10,?,?)", status, executor); }
    void guidance(String status) { jdbc.update("INSERT INTO workitem_comment_delivery (tenant_id,workitem_id,comment_id,target_agent_id,dispatch_id,status) VALUES (1,10,30,20,100,?)",status); }
    String guidanceStatus() { return jdbc.queryForObject("SELECT status FROM workitem_comment_delivery WHERE id=1",String.class); }
    boolean stopPending() { return !jdbc.queryForList(
            "SELECT 1 FROM dispatch_recovery WHERE dispatch_id=100 AND stop_pending=1").isEmpty(); }

    @Test void scheduledForceCancelPersistsStopAndRetriesOfflineExecutor() {
        dispatch("RUNNING", 50L);
        jdbc.update("UPDATE dispatch SET source_type='SCHEDULED_TASK_RUN' WHERE id=100");
        doThrow(new IllegalStateException("offline")).when(transport).pause(any());
        service.forceCancelScheduledRun(1, 10, 100, 7);
        assertEquals("CANCELED", dao.findById(100L).getStatus());
        assertTrue(service.cancelRequested(1, 100));
        assertTrue(stopPending());
        verify(transport).pause(any());
        jdbc.update("UPDATE dispatch_recovery SET last_sent_at=NULL WHERE dispatch_id=100");
        service.reconcile();
        verify(transport, times(2)).pause(any());
        assertFalse(service.onStopped(1, 51, 100));
        assertTrue(service.onStopped(1, 50, 100));
        assertFalse(stopPending());
        assertEquals("CANCELED", dao.findById(100L).getStatus());
    }

    @Test void scheduledForceCancelRejectsWrongSourceWorkspaceOrRun() {
        dispatch("RUNNING", 50L);
        assertThrows(BizException.class, () -> service.forceCancelScheduledRun(1, 10, 100, 7));
        jdbc.update("UPDATE dispatch SET source_type='SCHEDULED_TASK_RUN' WHERE id=100");
        assertThrows(BizException.class, () -> service.forceCancelScheduledRun(2, 10, 100, 7));
        assertThrows(BizException.class, () -> service.forceCancelScheduledRun(1, 11, 100, 7));
        verifyNoInteractions(transport);
        assertEquals("RUNNING", dao.findById(100L).getStatus());
    }

    @Test void timeoutKeepsStopPendingUntilRuntimeConfirmsRelease() {
        dispatch("DISPATCHED",50L); guidance("QUEUED");
        assertEquals(1,service.transition(dao.findById(100L),"TIMEOUT",null,null,null,null,"DISPATCH_ACK_TIMEOUT"));
        assertTrue(stopPending()); assertEquals("FAILED",guidanceStatus());
        assertTrue(service.onStopped(1,50,100)); assertFalse(stopPending());
        assertEquals("TIMEOUT",dao.findById(100L).getStatus());
    }

    @Test void serverFailureConvergesQueuedCommentButPreservesCompletedReply() {
        dispatch("PACKAGING",null); guidance("QUEUED");
        assertEquals(1,service.transition(dao.findById(100L),"FAILED",null,null,null,null,"BAD_PACKAGE"));
        assertEquals("FAILED",guidanceStatus());
        jdbc.update("UPDATE workitem_comment_delivery SET status='APPLIED',reply_comment_id=44");
        service.cancel(1,10,100,7,false);
        assertEquals("APPLIED",guidanceStatus());
    }
    @Test void terminalAndProjectionRollbackTogether() {
        dispatch("PACKAGING",null); guidance("QUEUED");
        jdbc.execute("DROP TABLE workitem_comment_delivery");
        assertThrows(RuntimeException.class,()->service.transition(dao.findById(100L),"FAILED",null,null,null,null,"error"));
        assertEquals("PACKAGING",dao.findById(100L).getStatus());
    }
    @Test void cancelBeforeDeliveryFencesPackagingWorkerWithoutSendingStop() {
        dispatch("PACKAGING",50L); guidance("QUEUED"); var stale=dao.findById(100L);
        service.cancel(1,10,100,7,false);
        assertEquals("CANCELED",dao.findById(100L).getStatus()); assertEquals("CANCELED",guidanceStatus());
        assertEquals(0,service.transition(stale,"DISPATCHED",null,50L,null,null,null));
        verifyNoInteractions(transport); assertFalse(stopPending());
    }
    @Test void forcedCancellationKeepsStopPendingUntilAuthenticatedStopAndCannotBeRevived() {
        dispatch("RUNNING",50L); guidance("DELIVERED"); var stale=dao.findById(100L);
        service.cancel(1,10,100,7,false); verify(transport).pause(any());
        assertEquals("PAUSING",dao.findById(100L).getStatus());
        service.cancel(1,10,100,7,true);
        assertEquals("CANCELED",dao.findById(100L).getStatus()); assertTrue(stopPending());
        assertTrue(service.fenced(dao.findById(100L))); assertFalse(service.onStopped(1,51,100));
        assertEquals(0,service.transition(stale,"SUCCEEDED",null,null,null,"late result",null));
        assertTrue(service.onStopped(1,50,100)); assertTrue(service.onStopped(1,50,100));
        assertFalse(stopPending()); assertEquals("CANCELED",dao.findById(100L).getStatus());
    }
    @Test void closeBlocksNewExecutionAndReopenDoesNotAutomaticallyRunAnything() {
        dispatch("PENDING",null); guidance("QUEUED"); service.close(1,10,7,false);
        assertTrue(service.closed(1,10)); assertEquals("CANCELED",guidanceStatus());
        var next=dao.findById(100L); next.setId(101L); next.setStatus("PENDING");
        assertThrows(BizException.class,()->service.insert(next));
        service.reopen(1,10,7); service.insert(next); assertNotNull(dao.findById(101L));
        verifyNoInteractions(transport);
    }
    @Test void retriesKeepOldGuidanceIdentitySoLateAckCannotApplyToNewAttempt() {
        dispatch("FAILED",null); guidance("FAILED");
        var next=dao.findById(100L);next.setId(101L);next.setStatus("PENDING");next.setResumeMode("CANONICAL_INTERACTION");next.setIdempotencyKey("continue:100");next.setResumeFromDispatchId(100L);
        service.insert(next);
        assertEquals("FAILED",guidanceStatus());
        assertEquals(List.of(100L,101L),jdbc.queryForList("SELECT dispatch_id FROM workitem_comment_delivery ORDER BY id",Long.class));
    }
    @Test void automaticPackagingRetriesAreBoundedAndPersistBackoff() {
        dispatch("PACKAGING",null);
        for(int n=0;n<3;n++) {
            jdbc.update("UPDATE dispatch SET status='PACKAGING'");
            assertTrue(service.retryPackaging(dao.findById(100L),"temporary network error"));
            assertFalse(service.ready(dao.findById(100L)));
        }
        jdbc.update("UPDATE dispatch SET status='PACKAGING'");
        assertFalse(service.retryPackaging(dao.findById(100L),"temporary network error"));
        assertEquals(3,jdbc.queryForObject("SELECT retry_count FROM dispatch_recovery",Integer.class));
    }
    @Test void reconcileRepairsHistoryAndRetriesDurableStopIntent() {
        dispatch("FAILED",null); guidance("QUEUED"); service.reconcile(); assertEquals("FAILED",guidanceStatus());
        jdbc.update("UPDATE dispatch SET status='RUNNING',executor_id=50");
        doThrow(new IllegalStateException("offline")).when(transport).pause(any());
        service.cancel(1,10,100,7,false);
        jdbc.update("UPDATE dispatch_recovery SET last_sent_at=?",new Timestamp(System.currentTimeMillis()-60_000));
        reset(transport); service.reconcile();verify(transport).pause(any());
        assertTrue(stopPending());
    }
    @Test void reconcileSelfHealsStopIntentTheLiveRuntimeRunningSetDisproves() {
        dispatch("DISPATCHED",50L);
        assertEquals(1,service.transition(dao.findById(100L),"TIMEOUT",null,null,null,null,"DISPATCH_ACK_TIMEOUT"));
        assertTrue(stopPending());
        jdbc.update("UPDATE dispatch_recovery SET requested_at=?,last_sent_at=?",
                new Timestamp(System.currentTimeMillis()-180_000),new Timestamp(System.currentTimeMillis()-60_000));
        var registry=registry(true,false); service.setExecutorRegistry(registry);
        service.reconcile();
        assertFalse(stopPending()); verifyNoInteractions(transport);
    }
    @Test void reconcileKeepsRetryingStopsTheLiveRuntimeCannotDisprove() {
        dispatch("DISPATCHED",50L);
        assertEquals(1,service.transition(dao.findById(100L),"TIMEOUT",null,null,null,null,"DISPATCH_ACK_TIMEOUT"));
        jdbc.update("UPDATE dispatch_recovery SET requested_at=?,last_sent_at=?",
                new Timestamp(System.currentTimeMillis()-180_000),new Timestamp(System.currentTimeMillis()-60_000));
        service.setExecutorRegistry(registry(false,false));
        service.reconcile();
        assertTrue(stopPending()); verify(transport).pause(any());
    }
    @Test void periodicStopReconciliationSupportsMysqlDatetimeMapping() {
        verifyMysqlDatetimeStopRetry(false);
    }

    @Test void heartbeatStopReconciliationSupportsMysqlDatetimeMapping() {
        verifyMysqlDatetimeStopRetry(true);
    }

    private void verifyMysqlDatetimeStopRetry(boolean heartbeat) {
        dispatch("RUNNING", 50L);
        service.cancel(1, 10, 100, 7, false);
        jdbc.update("UPDATE dispatch_recovery SET requested_at=?,last_sent_at=?",
                new Timestamp(System.currentTimeMillis() - 180_000),
                new Timestamp(System.currentTimeMillis() - 60_000));
        reset(transport);
        // Model Connector/J's DATETIME behavior, which H2's default map masks.
        var mysqlJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            protected org.springframework.jdbc.core.RowMapper<java.util.Map<String, Object>> getColumnMapRowMapper() {
                var mapper = super.getColumnMapRowMapper();
                return (rs, rowNum) -> {
                    var row = mapper.mapRow(rs, rowNum);
                    row.replaceAll((key, value) -> value instanceof Timestamp timestamp
                            ? timestamp.toLocalDateTime() : value);
                    return row;
                };
            }
        };
        assertInstanceOf(java.time.LocalDateTime.class,
                mysqlJdbc.queryForMap("SELECT requested_at FROM dispatch_recovery").get("requested_at"));
        var mysqlService = new DispatchRecoveryService(mysqlJdbc,
                new DataSourceTransactionManager(jdbc.getDataSource()), dao, workitems, transport);
        mysqlService.setExecutorRegistry(registry(true, true));
        if (heartbeat) mysqlService.reconcileExecutor(50L);
        else mysqlService.reconcile();
        verify(transport).pause(argThat(dispatch -> dispatch.getId() == 100L));
        assertTrue(stopPending());
        assertTrue(jdbc.queryForObject("SELECT last_sent_at FROM dispatch_recovery", Timestamp.class)
                .getTime() > System.currentTimeMillis() - 30_000);
    }
    @Test void reconcileDoesNotHealStopsYoungerThanTheHandshakeGrace() {
        dispatch("DISPATCHED",50L);
        assertEquals(1,service.transition(dao.findById(100L),"TIMEOUT",null,null,null,null,"DISPATCH_ACK_TIMEOUT"));
        jdbc.update("UPDATE dispatch_recovery SET requested_at=?,last_sent_at=?",
                new Timestamp(System.currentTimeMillis()-10_000),new Timestamp(System.currentTimeMillis()-60_000));
        service.setExecutorRegistry(registry(true,false));
        service.reconcile();
        assertTrue(stopPending()); verify(transport).pause(any());
    }

    @Test void reconcileHealsOldStopsWhenJdbcMapDatesAreLocalDateTime() {
        useLocalDateTimeMapResults();
        dispatch("DISPATCHED", 50L);
        assertEquals(1, service.transition(dao.findById(100L), "TIMEOUT", null, null, null, null, "DISPATCH_ACK_TIMEOUT"));
        jdbc.update("UPDATE dispatch_recovery SET requested_at=?,last_sent_at=?",
                new Timestamp(System.currentTimeMillis() - 180_000), new Timestamp(System.currentTimeMillis() - 60_000));
        assertInstanceOf(LocalDateTime.class, jdbc.queryForList("SELECT requested_at FROM dispatch_recovery").get(0).get("requested_at"));
        service.setExecutorRegistry(registry(true, false));

        assertDoesNotThrow(() -> service.reconcileExecutor(50L));

        assertFalse(stopPending());
        verifyNoInteractions(transport);
    }

    @Test void reconcilePreservesYoungStopGraceWhenJdbcMapDatesAreLocalDateTime() {
        useLocalDateTimeMapResults();
        dispatch("DISPATCHED", 50L);
        assertEquals(1, service.transition(dao.findById(100L), "TIMEOUT", null, null, null, null, "DISPATCH_ACK_TIMEOUT"));
        jdbc.update("UPDATE dispatch_recovery SET requested_at=?,last_sent_at=?",
                new Timestamp(System.currentTimeMillis() - 10_000), new Timestamp(System.currentTimeMillis() - 60_000));
        service.setExecutorRegistry(registry(true, false));

        assertDoesNotThrow(() -> service.reconcileExecutor(50L));

        assertTrue(stopPending());
        verify(transport).pause(any());
    }

    private void useLocalDateTimeMapResults() {
        // Connector/J returns DATETIME getObject values as LocalDateTime; H2
        // normally returns Timestamp. Preserve real SQL and shape only that boundary.
        var source = jdbc.getDataSource();
        jdbc = new JdbcTemplate(source) {
            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                return mysqlDates(super.queryForList(sql, args));
            }
            @Override public List<Map<String, Object>> queryForList(String sql) {
                return mysqlDates(super.queryForList(sql));
            }
            private List<Map<String, Object>> mysqlDates(List<Map<String, Object>> rows) {
                for (var row : rows) {
                    if (row.get("requested_at") instanceof Timestamp timestamp) {
                        row.put("requested_at", timestamp.toLocalDateTime());
                    }
                }
                return rows;
            }
        };
        service = new DispatchRecoveryService(jdbc, new DataSourceTransactionManager(source), dao, workitems, transport);
    }

    private com.aliyun.autowonder.executor.ExecutorRegistry registry(boolean reported,boolean active) {
        var registry=mock(com.aliyun.autowonder.executor.ExecutorRegistry.class);
        when(registry.isOnline(50L)).thenReturn(true);
        if (reported) {
            var owned = active ? java.util.Set.of(100L) : java.util.Set.<Long>of();
            var snapshot = new com.aliyun.autowonder.executor.ExecutorDispatchSnapshot(
                    "current", 10, true, true, owned, owned, java.util.Set.of(),
                    java.util.Set.of(), null, System.currentTimeMillis());
            when(registry.currentDispatchSnapshot(50L)).thenReturn(java.util.Optional.of(snapshot));
        } else {
            when(registry.currentDispatchSnapshot(50L)).thenReturn(java.util.Optional.empty());
        }
        return registry;
    }
    @Test void successfulReplyWithoutAckIsNotInvented() {
        dispatch("SUCCEEDED",null); guidance("DELIVERED");
        jdbc.update("UPDATE dispatch SET gmt_modified=?",new Timestamp(System.currentTimeMillis()-180_000));
        service.reconcile(); assertEquals("FAILED",guidanceStatus());
    }
}
