package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.executor.ExecutorDispatchSnapshot;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.PresenceManager;
import com.aliyun.autowonder.workitem.WorkitemClassificationEvaluator;
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
    DispatchRuntimeEventDao runtimeEvents;
    DispatchCheckpointDao checkpoints;

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE dispatch (id BIGINT PRIMARY KEY, tenant_id BIGINT, workitem_id BIGINT, executor_id BIGINT, status VARCHAR(32),source_type VARCHAR(32),resume_mode VARCHAR(32),error VARCHAR(512),version INT DEFAULT 0,gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE workitem_comment_delivery (id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,workitem_id BIGINT,comment_id BIGINT,target_agent_id BIGINT,dispatch_id BIGINT,executor_id BIGINT,status VARCHAR(16),error VARCHAR(1024),reply_comment_id BIGINT,delivered_at TIMESTAMP,applied_at TIMESTAMP,gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,retry_dispatch_id BIGINT DEFAULT 0)");
        String schema = Files.readString(Path.of("docs/migration/V059__dispatch_recovery.sql")).split("ALTER TABLE")[0].replaceAll("(?m)^--.*$", "");
        for (String sql : schema.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        dao = mock(DispatchDao.class); workitems = mock(WorkitemDao.class); transport = mock(DispatchControlTransport.class);
        service = new DispatchRecoveryService(jdbc, new DataSourceTransactionManager(ds), dao, workitems, transport);
        runtimeEvents = mock(DispatchRuntimeEventDao.class); checkpoints = mock(DispatchCheckpointDao.class);
        service.setCompletionEvidence(runtimeEvents, checkpoints);
        var w = new WorkitemDO(); w.setTenantId(1L); w.setId(10L); when(workitems.findById(10L)).thenReturn(w); when(workitems.findByIdForUpdate(10L, 1L)).thenReturn(w);
        when(dao.findById(anyLong())).thenAnswer(i -> jdbc.query("SELECT * FROM dispatch WHERE id=?", (rs,n) -> {
            var d = new DispatchDO(); d.setId(rs.getLong("id")); d.setTenantId(rs.getLong("tenant_id"));
            d.setWorkitemId(rs.getLong("workitem_id")); d.setSourceType(rs.getString("source_type")); d.setStatus(rs.getString("status")); d.setVersion(rs.getInt("version"));
            d.setExecutorId((Long) rs.getObject("executor_id")); d.setError(rs.getString("error")); d.setAgentId(20L); d.setAttempt(1); d.setSdlcStepId(77L);
            d.setResumeMode(rs.getString("resume_mode"));
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

    @Test void missingRunningExecutionCreatesFencedRecoveryAtSameStep() {
        orphanInventory("RUNNING", true, java.util.Set.of());
        var next = service.recoverAbandonedExecution(dao.findById(100L), "session");
        assertNotNull(next);
        assertEquals("CANCELED", dao.findById(100L).getStatus());
        assertEquals(100L, next.getResumeFromDispatchId());
        assertEquals(77L, next.getSdlcStepId());
        assertEquals("RECOVERY", next.getResumeMode());
        assertEquals("continue:100", next.getIdempotencyKey());
        assertEquals("PENDING", next.getStatus());
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
    }

    @Test void handoffFenceReadsCurrentStatusInsteadOfStaleSourceObject() {
        dispatch("RUNNING", 50L);
        var stale = dao.findById(100L);
        jdbc.update("UPDATE dispatch SET status='CANCELED' WHERE id=100");
        assertTrue(service.isHandoffSourceFenced(stale));
        jdbc.update("UPDATE dispatch SET status='SUCCEEDED' WHERE id=100");
        assertFalse(service.isHandoffSourceFenced(stale));
    }

    @Test void deliveredHandoffToAnotherWorkerPreventsDuplicateSourceRecovery() {
        orphanInventory("RUNNING", true, java.util.Set.of());
        var downstream = new DispatchDO(); downstream.setId(102L); downstream.setAgentId(21L);
        downstream.setStatus(DispatchStatus.RUNNING); downstream.setIdempotencyKey("handoff:100");
        var source = dao.findById(100L);
        doReturn(List.of(source, downstream)).when(dao).listByWorkitem(1L, 10L);
        when(dao.findByIdempotencyKey(1L, "handoff:100")).thenReturn(downstream);
        assertNull(service.recoverAbandonedExecution(source, "session"));
        verify(dao, never()).insert(any());
        assertEquals(DispatchStatus.PAUSED, dao.findById(100L).getStatus());
        assertFalse(service.cancelRequested(1, 100));
        assertFalse(stopPending());
    }

    @Test void durableHumanOwnershipPreventsAutomaticFormalExecutionRecovery() {
        orphanInventory("RUNNING", true, java.util.Set.of());
        WorkitemDO subject = workitems.findById(10L);
        subject.setAssigneeType("HUMAN"); subject.setAssigneeRef(7L);
        when(workitems.findByIdForUpdate(10L, 1L)).thenReturn(subject);
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
        verify(dao, never()).insert(any());
        assertEquals(DispatchStatus.PAUSED, dao.findById(100L).getStatus());
        assertEquals("HUMAN", subject.getAssigneeType());
        assertEquals(7L, subject.getAssigneeRef());
        assertFalse(service.cancelRequested(1, 100));
        assertFalse(stopPending());
        verifyNoInteractions(transport);
    }

    @Test void supersededWorkerExecutionDoesNotCreateAnotherRecovery() {
        orphanInventory("RUNNING", true, java.util.Set.of());
        var newer = new DispatchDO(); newer.setId(102L); newer.setAgentId(20L);
        newer.setStatus("RUNNING"); newer.setSdlcStepId(78L);
        var source = dao.findById(100L);
        doReturn(List.of(source, newer)).when(dao).listByWorkitem(1L, 10L);
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
        verify(dao, never()).insert(any());
        assertEquals("PAUSED", dao.findById(100L).getStatus());
        assertFalse(service.cancelRequested(1, 100));
        assertFalse(stopPending());
    }

    @Test void completedHumanHandoffReleasesCapacityWithoutChangingHumanWaitOrRestarting() {
        orphanInventory("RUNNING", true, java.util.Set.of());
        WorkitemDO subject = workitems.findById(10L);
        subject.setAssigneeType("HUMAN"); subject.setAssigneeRef(7L); subject.setStatusNodeId(88L);
        completionEvidence();
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
        assertEquals("SUCCEEDED", dao.findById(100L).getStatus());
        assertEquals("HUMAN", subject.getAssigneeType());
        assertEquals(7L, subject.getAssigneeRef());
        assertEquals(88L, subject.getStatusNodeId());
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_PENDING_DECISION,
                WorkitemClassificationEvaluator.classify("IN_PROGRESS", subject.getAssigneeType(),
                        subject.getAssigneeRef(), dao.listByWorkitem(1L, 10L)));
        verify(workitems, never()).updateAssignee(anyLong(), anyLong(), anyString(), anyLong(), anyInt(), anyLong());
        verify(dao, never()).insert(any());
        assertFalse(service.closed(1, 10));
        assertFalse(service.cancelRequested(1, 100));
        assertFalse(stopPending());
        verifyNoInteractions(transport);
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
    }

    @Test void completedSideInteractionWithSuccessorIsSucceededAndKeepsItsReply() {
        orphanInventory("RUNNING", true, java.util.Set.of());
        jdbc.update("UPDATE dispatch SET resume_mode='SIDE_INTERACTION' WHERE id=100");
        guidance("APPLIED"); jdbc.update("UPDATE workitem_comment_delivery SET reply_comment_id=44");
        jdbc.update("INSERT INTO dispatch (id,tenant_id,workitem_id,status) VALUES (102,1,10,'RUNNING')");
        completionEvidence();
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
        assertEquals("SUCCEEDED", dao.findById(100L).getStatus());
        assertEquals("RUNNING", dao.findById(102L).getStatus());
        assertEquals("APPLIED", guidanceStatus());
        assertEquals(44L, jdbc.queryForObject("SELECT reply_comment_id FROM workitem_comment_delivery WHERE id=1", Long.class));
        verify(dao, never()).insert(any());
        verifyNoInteractions(transport);
        assertFalse(service.cancelRequested(1, 100));
        assertFalse(stopPending());
    }

    @Test void repliedInteractionWithoutSuccessorDoesNotRepeatTheUsersRequest() {
        orphanInventory("RUNNING", true, java.util.Set.of());
        jdbc.update("UPDATE dispatch SET resume_mode='CANONICAL_INTERACTION' WHERE id=100");
        guidance("APPLIED"); jdbc.update("UPDATE workitem_comment_delivery SET reply_comment_id=44");
        // The reply is durable, but no completion receipt: keep a resumable record, not false success.
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
        assertEquals("PAUSED", dao.findById(100L).getStatus());
        assertEquals("APPLIED", guidanceStatus());
        verify(dao, never()).insert(any());
        assertFalse(stopPending());
    }

    @Test void completionEvidenceNeverReleasesARuntimeOwnedOrFreshlyClaimedExecution() {
        orphanInventory("RUNNING", true, java.util.Set.of(100L));
        workitems.findById(10L).setAssigneeType("HUMAN");
        completionEvidence();
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
        assertEquals("RUNNING", dao.findById(100L).getStatus());
        jdbc.update("DELETE FROM dispatch");
        orphanInventory("ACKED", true, java.util.Set.of());
        jdbc.update("UPDATE dispatch SET gmt_modified=NOW() WHERE id=100");
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
        assertEquals("ACKED", dao.findById(100L).getStatus());
        verifyNoInteractions(runtimeEvents, checkpoints, transport);
    }

    @Test void invalidCheckpointCannotTurnHumanWaitIntoSuccessfulDelivery() {
        orphanInventory("RUNNING", true, java.util.Set.of());
        workitems.findById(10L).setAssigneeType("HUMAN");
        DispatchCheckpointDO checkpoint = completionEvidence();
        checkpoint.setExecutorId(999L);
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
        assertEquals("PAUSED", dao.findById(100L).getStatus());
        verify(dao, never()).insert(any());
        assertFalse(service.cancelRequested(1, 100));
    }

    @Test void runningInventoryPreventsReleaseEvenWhenOwnedInventoryOmitsExecution() {
        orphanInventory("RUNNING", true, java.util.Set.of());
        workitems.findById(10L).setAssigneeType("HUMAN");
        var registry = mock(ExecutorRegistry.class);
        when(registry.currentDispatchSnapshot(50L)).thenReturn(java.util.Optional.of(
                new ExecutorDispatchSnapshot("session", 2, true, true, java.util.Set.of(100L),
                        java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), null, System.currentTimeMillis())));
        service.setExecutorRegistry(registry);
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
        assertEquals("RUNNING", dao.findById(100L).getStatus());
        verifyNoInteractions(runtimeEvents, checkpoints, transport);
        verify(dao, never()).insert(any());
    }

    DispatchCheckpointDO completionEvidence() {
        var completed = new DispatchRuntimeEventDO(); completed.setSeq(30L);
        completed.setDetailJson("{\"state\":\"completed\"}");
        var terminal = new DispatchRuntimeEventDO(); terminal.setSeq(27L);
        terminal.setDetailJson("{\"reason\":\"terminal\",\"checkpointSeq\":70,\"sha256\":\"sha256:abc\"}");
        when(runtimeEvents.findLatestByDispatchAndType(1L, 100L, "dispatch.completed")).thenReturn(completed);
        when(runtimeEvents.findLatestByDispatchAndType(1L, 100L, "runtime.checkpoint_saved")).thenReturn(terminal);
        var checkpoint = new DispatchCheckpointDO(); checkpoint.setExecutorId(50L);
        checkpoint.setSha256("abc"); checkpoint.setOssRef("bucket/terminal-checkpoint");
        when(checkpoints.findByDispatchAndSeq(1L, 100L, 70L)).thenReturn(checkpoint);
        return checkpoint;
    }

    @Test void releasedHumanWaitAllowsQueuedWorkToSelectExecutorWithProductionCapacityQuery() throws Exception {
        orphanInventory("RUNNING", true, java.util.Set.of());
        workitems.findById(10L).setAssigneeType("HUMAN");
        workitems.findById(10L).setAssigneeRef(7L);
        jdbc.execute("ALTER TABLE dispatch ADD is_deleted INT DEFAULT 0");
        var config = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment(
                "capacity", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), jdbc.getDataSource()));
        config.setDatabaseId("autowonder-source-aware");
        try (var stream = getClass().getResourceAsStream("/mapping/DispatchDao.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(stream, config, "capacity.xml", config.getSqlFragments()).parse();
        }
        try (var session = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(config).openSession(true)) {
            DispatchDao realDao = session.getMapper(DispatchDao.class);
            var registry = mock(ExecutorRegistry.class);
            when(registry.isAvailable(50L)).thenReturn(true);
            // Capacity 2 reserves one slot for interactions, leaving one normal-work slot.
            when(registry.currentDispatchSnapshot(50L)).thenReturn(java.util.Optional.of(
                    new ExecutorDispatchSnapshot("session", 2, true, true, java.util.Set.of(),
                            java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), null, System.currentTimeMillis())));
            var redis = mock(RedisManager.class);
            when(redis.smembers(ExecutorSelector.execsKey(20L))).thenReturn(java.util.Set.of("50"));
            var selector = new ExecutorSelector(redis, registry, mock(PresenceManager.class), realDao);
            assertEquals(List.of(100L), realDao.listCapacityOccupyingIds(50L));
            assertNull(selector.select(20L));
            assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
            session.clearCache();
            assertTrue(realDao.listCapacityOccupyingIds(50L).isEmpty());
            assertEquals(50L, selector.select(20L));
            assertEquals("PAUSED", dao.findById(100L).getStatus());
            assertEquals(WorkitemClassificationEvaluator.CATEGORY_PENDING_DECISION,
                    WorkitemClassificationEvaluator.classify("IN_PROGRESS", "HUMAN", 7L,
                            dao.listByWorkitem(1L, 10L)));
            assertFalse(service.cancelRequested(1, 100));
        }
    }

    @Test void malformedCompletionProofPreservesHumanWaitWithoutClaimingSuccess() {
        orphanInventory("RUNNING", true, java.util.Set.of());
        workitems.findById(10L).setAssigneeType("HUMAN");
        completionEvidence();
        runtimeEvents.findLatestByDispatchAndType(1L, 100L, "dispatch.completed").setDetailJson("invalid");
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
        assertEquals("PAUSED", dao.findById(100L).getStatus());
        verify(dao, never()).insert(any());
        assertFalse(service.cancelRequested(1, 100));
    }

    @Test void recoveryInsertFailureRollsBackOldExecutionFence() {
        orphanInventory("RUNNING", true, java.util.Set.of());
        doThrow(new IllegalStateException("insert unavailable")).when(dao).insert(any());
        assertThrows(IllegalStateException.class,
                () -> service.recoverAbandonedExecution(dao.findById(100L), "session"));
        assertEquals("RUNNING", dao.findById(100L).getStatus());
    }

    @Test void recoveryDoesNotReviveTimeoutOrCanceledOrOwnedExecution() {
        for (String status : List.of("TIMEOUT", "CANCELED", "PAUSED", "PAUSING")) {
            jdbc.update("DELETE FROM dispatch");
            orphanInventory(status, true, java.util.Set.of());
            assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
            assertEquals(status, dao.findById(100L).getStatus());
        }
        jdbc.update("DELETE FROM dispatch");
        orphanInventory("RUNNING", true, java.util.Set.of(100L));
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
        verify(dao, never()).insert(any());
    }

    @Test void recentClaimAndUnknownOrReplacedInventoryPreventRecovery() {
        orphanInventory("ACKED", true, java.util.Set.of());
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "old-session"));
        jdbc.update("UPDATE dispatch SET gmt_modified=NOW() WHERE id=100");
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
        jdbc.update("DELETE FROM dispatch");
        orphanInventory("RUNNING", false, java.util.Set.of());
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
        verify(dao, never()).insert(any());
    }

    @Test void durableCancelOrClosedWorkitemPreventsAutomaticRecovery() {
        orphanInventory("RUNNING", true, java.util.Set.of());
        jdbc.update("INSERT INTO dispatch_recovery (tenant_id,dispatch_id,cancel_requested) VALUES (1,100,1)");
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
        jdbc.update("DELETE FROM dispatch_recovery");
        jdbc.update("INSERT INTO workitem_execution_control (tenant_id,workitem_id,closed) VALUES (1,10,1) ON DUPLICATE KEY UPDATE closed=1");
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
        verify(dao, never()).insert(any());
    }

    void orphanInventory(String status, boolean authoritative, java.util.Set<Long> owned) {
        dispatch(status, 50L);
        jdbc.update("UPDATE dispatch SET gmt_modified=? WHERE id=100", new Timestamp(System.currentTimeMillis()-180_000));
        var registry = mock(com.aliyun.autowonder.executor.ExecutorRegistry.class);
        when(registry.currentDispatchSnapshot(50L)).thenReturn(java.util.Optional.of(
                new com.aliyun.autowonder.executor.ExecutorDispatchSnapshot("session", 10,
                        authoritative, true, java.util.Set.of(), owned, java.util.Set.of(),
                        java.util.Set.of(), null, System.currentTimeMillis())));
        service.setExecutorRegistry(registry);
        doAnswer(i -> {
            DispatchDO d = i.getArgument(0); d.setId(101L);
            jdbc.update("INSERT INTO dispatch (id,tenant_id,workitem_id,status) VALUES (?,?,?,?)",
                    d.getId(),d.getTenantId(),d.getWorkitemId(),d.getStatus());
            return null;
        }).when(dao).insert(any());
    }

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
