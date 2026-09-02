package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.storage.ObjectStorageException;
import com.aliyun.autowonder.taskpackage.PackageContext;
import com.aliyun.autowonder.taskpackage.TaskPackageResult;
import com.aliyun.autowonder.taskpackage.TaskPackager;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchServiceRunPendingTest {

    private DispatchDao dispatchDao;
    private WorkitemDao workitemDao;
    private AgentDao agentDao;
    private AgentVersionDao agentVersionDao;
    private ExecutorSelector executorSelector;
    private PackageContextAssembler assembler;
    private TaskPackager taskPackager;
    private DispatchTransport transport;
    private SdlcDriver sdlcDriver;
    private RedisManager redisManager;
    private DispatchCheckpointService checkpointService;
    private DispatchService service;

    private static final long TENANT = 100L;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        workitemDao = mock(WorkitemDao.class);
        agentDao = mock(AgentDao.class);
        agentVersionDao = mock(AgentVersionDao.class);
        executorSelector = mock(ExecutorSelector.class);
        assembler = mock(PackageContextAssembler.class);
        taskPackager = mock(TaskPackager.class);
        transport = mock(DispatchTransport.class);
        sdlcDriver = mock(SdlcDriver.class);
        redisManager = mock(RedisManager.class);
        checkpointService = mock(DispatchCheckpointService.class);
        service = new DispatchService(dispatchDao, mock(DispatchRuntimeEventDao.class), workitemDao, agentDao, agentVersionDao,
                executorSelector, assembler, taskPackager, transport, sdlcDriver,
                redisManager, checkpointService);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(dispatchDao.updateStatus(anyLong(), anyLong(), anyString(), any(), any(),
                any(), any(), any(), anyInt(), anyLong())).thenReturn(1);
        when(dispatchDao.findById(650L)).thenReturn(workitemSource(650L));
        when(dispatchDao.findById(700L)).thenReturn(workitemSource(700L));
    }

    private DispatchDO workitemSource(long id) {
        DispatchDO source = pending();
        source.setId(id);
        source.setSourceType(ExecutionSourceType.WORKITEM.name());
        return source;
    }

    private DispatchDO pending() {
        DispatchDO d = new DispatchDO();
        d.setId(500L);
        d.setTenantId(TENANT);
        d.setWorkitemId(200L);
        d.setSdlcStepId(300L);
        d.setAgentId(400L);
        d.setStatus(DispatchStatus.PENDING);
        d.setAttempt(1);
        d.setIdempotencyKey("200:300:1");
        d.setVersion(0);
        return d;
    }

    private AgentDO onlineAgent() {
        AgentDO a = new AgentDO();
        a.setId(400L);
        a.setTenantId(TENANT);
        a.setStatus("ONLINE");
        a.setOnlineVersionId(410L);
        a.setVersion(0);
        return a;
    }

    private AgentVersionDO onlineVersion() {
        AgentVersionDO v = new AgentVersionDO();
        v.setId(410L);
        v.setTenantId(TENANT);
        v.setAgentId(400L);
        return v;
    }

    @Test
    void scheduledPinAcceptsAnEqualVersionAlreadyWonByRecovery() {
        DispatchDO initial = scheduledPending(500L, null);
        DispatchDO recovered = scheduledPending(500L, 410L);
        recovered.setStatus(DispatchStatus.DISPATCHED);
        when(dispatchDao.findById(500L)).thenReturn(initial, recovered);
        when(dispatchDao.pinScheduledAgentVersion(500L, TENANT, 400L, 410L, 0L)).thenReturn(0);

        service.pinScheduledAgentVersion(500L, TENANT, 410L);

        verify(dispatchDao).pinScheduledAgentVersion(500L, TENANT, 400L, 410L, 0L);
    }

    @Test
    void scheduledPinRejectsADifferentVersionWonByRecovery() {
        DispatchDO initial = scheduledPending(500L, null);
        DispatchDO recovered = scheduledPending(500L, 411L);
        recovered.setStatus(DispatchStatus.DISPATCHED);
        when(dispatchDao.findById(500L)).thenReturn(initial, recovered);
        when(dispatchDao.pinScheduledAgentVersion(500L, TENANT, 400L, 410L, 0L)).thenReturn(0);

        BizException error = assertThrows(BizException.class,
                () -> service.pinScheduledAgentVersion(500L, TENANT, 410L));

        assertEquals(ErrorCode.SCHEDULED_TASK_INVALID_STATE.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("pin was lost"));
    }

    private DispatchDO scheduledPending(long id, Long versionId) {
        DispatchDO dispatch = pending();
        dispatch.setId(id);
        dispatch.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        dispatch.setAgentVersionId(versionId);
        return dispatch;
    }

    @Test
    void enqueueRecognizesPastNamespacedWorkitemKeyWhenRawKeyIsAbsent() {
        DispatchDO existing = pending();
        when(dispatchDao.findByIdempotencyKey(TENANT, "200:300:1")).thenReturn(null);
        when(dispatchDao.findByIdempotencyKey(TENANT, "WORKITEM:200:300:1")).thenReturn(existing);
        DispatchDO out = service.enqueue(TENANT, 200L, 300L, 400L, 1, 0L);
        assertSame(existing, out);
        verify(dispatchDao, never()).insert(any());
    }

    @Test
    void enqueueStillRecognizesLegacyUnprefixedWorkitemKeyDuringUpgrade() {
        DispatchDO existing = pending();
        when(dispatchDao.findByIdempotencyKey(TENANT, "200:300:1")).thenReturn(existing);

        DispatchDO out = service.enqueue(TENANT, 200L, 300L, 400L, 1, 0L);

        assertSame(existing, out);
        verify(dispatchDao, never()).insert(any());
    }

    @Test
    void enqueueInsertsPendingWhenNew() {
        DispatchDO out = service.enqueue(TENANT, 200L, 300L, 400L, 1, 7L);
        assertEquals(DispatchStatus.PENDING, out.getStatus());
        assertEquals(ExecutionSourceType.WORKITEM.name(), out.getSourceType());
        assertEquals("200:300:1", out.getIdempotencyKey());
        assertEquals(1, out.getAttempt());
        verify(dispatchDao).insert(out);
    }

    @Test
    void enqueueSubjectSeparatesEqualNumericIdsAndSupportsScheduledRootDispatch() {
        DispatchDO workitem = service.enqueueSubject(TENANT, ExecutionSourceType.WORKITEM,
                200L, 300L, 400L, 1, 7L);
        DispatchDO scheduledRun = service.enqueueSubject(TENANT, ExecutionSourceType.SCHEDULED_TASK_RUN,
                200L, null, 400L, 1, 7L);

        assertEquals("200:300:1", workitem.getIdempotencyKey());
        assertEquals("SCHEDULED_TASK_RUN:200:root:1", scheduledRun.getIdempotencyKey());
        assertEquals(ExecutionSourceType.SCHEDULED_TASK_RUN.name(), scheduledRun.getSourceType());
        assertEquals(200L, scheduledRun.getWorkitemId());
        verify(dispatchDao, times(2)).insert(any(DispatchDO.class));
    }

    @Test
    void directWorkitemEnqueueRejectsRootStepBeforeAnyDatabaseAccess() {
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueueSubject(TENANT, ExecutionSourceType.WORKITEM,
                        200L, null, 400L, 1, 7L));

        verify(dispatchDao, never()).findByIdempotencyKey(anyLong(), anyString());
        verify(dispatchDao, never()).insert(any());
    }

    @Test
    void workitemRawInsertRaceRecoversPastNamespacedWinner() {
        DispatchDO namespacedWinner = pending();
        namespacedWinner.setId(900L);
        when(dispatchDao.findByIdempotencyKey(TENANT, "200:300:1"))
                .thenReturn(null, null);
        when(dispatchDao.findByIdempotencyKey(TENANT, "WORKITEM:200:300:1"))
                .thenReturn(null, namespacedWinner);
        doThrow(new DuplicateKeyException("normalized key race"))
                .when(dispatchDao).insert(any(DispatchDO.class));

        DispatchDO out = service.enqueueSubject(TENANT, ExecutionSourceType.WORKITEM,
                200L, 300L, 400L, 1, 7L);

        assertSame(namespacedWinner, out);
        verify(dispatchDao, times(2)).findByIdempotencyKey(TENANT, "WORKITEM:200:300:1");
    }

    @Test
    void scheduledRunDuplicateKeyDoesNotFallBackToWorkitemLegacyKey() {
        when(dispatchDao.findByIdempotencyKey(TENANT, "SCHEDULED_TASK_RUN:200:300:1"))
                .thenReturn(null, null);
        doThrow(new DuplicateKeyException("unrelated duplicate"))
                .when(dispatchDao).insert(any(DispatchDO.class));

        assertThrows(DuplicateKeyException.class,
                () -> service.enqueueSubject(TENANT, ExecutionSourceType.SCHEDULED_TASK_RUN,
                        200L, 300L, 400L, 1, 7L));

        verify(dispatchDao, never()).findByIdempotencyKey(TENANT, "200:300:1");
    }

    @Test
    void dispatchSafelyInterpretsLegacyNullSourceAsWorkitem() {
        DispatchDO legacy = new DispatchDO();
        assertEquals(ExecutionSourceType.WORKITEM, legacy.executionSourceType());
        legacy.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        assertEquals(ExecutionSourceType.SCHEDULED_TASK_RUN, legacy.executionSourceType());
    }

    @Test
    void assignmentAllocatesFreshAttemptAndUsesAssignmentVersionKey() {
        when(dispatchDao.findByIdempotencyKey(TENANT, "assignment:200:300:401:8")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(TENANT, 200L, 300L)).thenReturn(4);
        DispatchDO previousWorker = dispatch(700L, 400L, "200:300:4", null);
        previousWorker.setStatus(DispatchStatus.SUCCEEDED);
        when(dispatchDao.listByWorkitem(TENANT, 200L)).thenReturn(List.of(previousWorker));

        DispatchDO out = service.enqueueAssignment(TENANT, 200L, 300L, 401L, 8, 7L);

        assertEquals(5, out.getAttempt());
        assertEquals(401L, out.getAgentId());
        assertEquals("assignment:200:300:401:8", out.getIdempotencyKey());
        assertEquals(700L, out.getDeliverySourceDispatchId());
        assertNull(out.getResumeFromDispatchId(),
                "a new worker must inherit delivery without resuming the previous worker session");
        verify(dispatchDao).insert(out);
    }

    @Test
    void successfulAssignmentBecomesNextWorkersAuthoritativeDeliverySource() {
        when(dispatchDao.findByIdempotencyKey(TENANT, "assignment:200:300:402:9")).thenReturn(null);
        DispatchDO firstWorker = dispatch(700L, 400L, "200:300:4", null);
        firstWorker.setStatus(DispatchStatus.SUCCEEDED);
        DispatchDO successfulAssignment = dispatch(701L, 401L, "assignment:200:300:401:8", null);
        successfulAssignment.setStatus(DispatchStatus.SUCCEEDED);
        successfulAssignment.setDeliverySourceDispatchId(700L);
        when(dispatchDao.listByWorkitem(TENANT, 200L))
                .thenReturn(List.of(firstWorker, successfulAssignment));

        DispatchDO out = service.enqueueAssignment(TENANT, 200L, 300L, 402L, 9, 7L);

        assertEquals(701L, out.getDeliverySourceDispatchId(),
                "the latest successful worker delivery must not be unwrapped to its predecessor");
    }

    @Test
    void failedLatestDispatchDoesNotReplaceLastSuccessfulDeliverySource() {
        when(dispatchDao.findByIdempotencyKey(TENANT, "assignment:200:300:402:9")).thenReturn(null);
        DispatchDO accepted = dispatch(700L, 400L, "200:300:4", null);
        accepted.setStatus(DispatchStatus.SUCCEEDED);
        DispatchDO failed = dispatch(701L, 401L, "assignment:200:300:401:8", null);
        failed.setStatus(DispatchStatus.FAILED);
        failed.setDeliverySourceDispatchId(700L);
        when(dispatchDao.listByWorkitem(TENANT, 200L)).thenReturn(List.of(accepted, failed));

        DispatchDO out = service.enqueueAssignment(TENANT, 200L, 300L, 402L, 9, 7L);

        assertEquals(700L, out.getDeliverySourceDispatchId());
    }

    @Test
    void assignmentEventReplayReturnsItsExistingDispatch() {
        DispatchDO existing = pending();
        existing.setAgentId(401L);
        existing.setAttempt(5);
        existing.setIdempotencyKey("assignment:200:300:401:8");
        when(dispatchDao.findByIdempotencyKey(TENANT, "assignment:200:300:401:8")).thenReturn(existing);

        DispatchDO out = service.enqueueAssignment(TENANT, 200L, 300L, 401L, 8, 7L);

        assertSame(existing, out);
        verify(dispatchDao, never()).findMaxAttempt(anyLong(), anyLong(), anyLong());
        verify(dispatchDao, never()).insert(any());
    }

    @Test
    void enqueueHandoffAllocatesNextAttemptAndUsesSourceKey() {
        when(dispatchDao.findByIdempotencyKey(TENANT, "handoff:700")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(TENANT, 200L, 300L)).thenReturn(2);

        DispatchDO out = service.enqueueHandoff(TENANT, 200L, 300L, 400L, 700L, 7L);

        assertEquals(DispatchStatus.PENDING, out.getStatus());
        assertEquals("handoff:700", out.getIdempotencyKey());
        assertEquals(3, out.getAttempt());
        assertEquals(700L, out.getDeliverySourceDispatchId());
        verify(dispatchDao).insert(out);
    }

    @Test
    void enqueueHandoffRejectsScheduledSourceBeforeAttemptOrIdempotencyChecks() {
        DispatchDO scheduled = pending();
        scheduled.setId(700L);
        scheduled.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        when(dispatchDao.findById(700L)).thenReturn(scheduled);

        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> service.enqueueHandoff(TENANT, 200L, 300L, 400L, 700L, 7L));

        verify(dispatchDao, never()).findByIdempotencyKey(anyLong(), anyString());
        verify(dispatchDao, never()).findMaxAttempt(anyLong(), anyLong(), anyLong());
        verify(dispatchDao, never()).insert(any());
    }

    @Test
    void commentInteractionUsesSideForkOnlyWhileSourceTurnIsActive() {
        when(dispatchDao.findByIdempotencyKey(TENANT, "guidance:701")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(TENANT, 200L, 300L)).thenReturn(2);

        DispatchDO out = service.enqueueCommentInteraction(
                TENANT, 200L, 400L, 650L, true, 300L, 701L, 7L);

        assertEquals("SIDE_INTERACTION", out.getResumeMode());
        assertEquals(650L, out.getResumeFromDispatchId());
        assertEquals("guidance:701", out.getIdempotencyKey());
        verify(dispatchDao).insert(out);
    }

    @Test
    void commentInteractionRejectsScheduledResumeSource() {
        DispatchDO scheduled = pending();
        scheduled.setId(650L);
        scheduled.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        when(dispatchDao.findById(650L)).thenReturn(scheduled);

        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> service.enqueueCommentInteraction(
                        TENANT, 200L, 400L, 650L, true, 300L, 701L, 7L));

        verify(dispatchDao, never()).insert(any());
    }

    @Test
    void interactionReworkValidatesEveryDispatchReference() {
        for (int invalidIndex = 0; invalidIndex < 3; invalidIndex++) {
            reset(dispatchDao);
            DispatchDO workitem = pending();
            DispatchDO scheduled = pending();
            scheduled.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
            long[] ids = {601L, 602L, 603L};
            for (int i = 0; i < ids.length; i++) {
                DispatchDO row = i == invalidIndex ? scheduled : workitem;
                row.setId(ids[i]);
                when(dispatchDao.findById(ids[i])).thenReturn(row);
            }

            assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                    () -> service.enqueueInteractionRework(TENANT, 200L, 400L, 300L,
                            ids[0], ids[1], ids[2], 7L), "invalid ref index " + invalidIndex);
            verify(dispatchDao, never()).insert(any());
        }
    }

    @Test
    void workitemOnlyFencedControlsIgnoreScheduledRunDispatches() {
        DispatchDO scheduled = pending();
        scheduled.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        scheduled.setAgentVersionId(410L);
        when(dispatchDao.findById(500L)).thenReturn(scheduled);

        assertFalse(service.releaseInteractionRework(TENANT, 500L));
        assertFalse(service.cancelWaitingInteractionRework(TENANT, 500L));
        assertFalse(service.cancelPauseFailedIfExecutorReleased(TENANT, 500L));
        assertFalse(service.cancelUndeliveredForInteraction(TENANT, 500L));

        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void idleOrNewWorkerCommentUsesCanonicalInteraction() {
        when(dispatchDao.findByIdempotencyKey(TENANT, "guidance:702")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(TENANT, 200L, 300L)).thenReturn(null);
        DispatchDO previousWorker = dispatch(700L, 399L, "200:299:1", null);
        previousWorker.setStatus(DispatchStatus.SUCCEEDED);
        when(dispatchDao.listByWorkitem(TENANT, 200L)).thenReturn(List.of(previousWorker));

        DispatchDO out = service.enqueueCommentInteraction(
                TENANT, 200L, 400L, null, false, 300L, 702L, 7L);

        assertEquals("CANONICAL_INTERACTION", out.getResumeMode());
        assertNull(out.getResumeFromDispatchId());
        assertEquals(700L, out.getDeliverySourceDispatchId());
        assertEquals(1, out.getAttempt());
        verify(dispatchDao).insert(out);
    }

    @Test
    void commentReworkResumesNewWorkerSessionButInheritsPreviousWorkerDelivery() {
        when(dispatchDao.findByIdempotencyKey(TENANT, "interaction-rework:103")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(TENANT, 200L, 300L)).thenReturn(5);
        DispatchDO canonicalInteraction = dispatch(103L, 401L, "guidance:702", "CANONICAL_INTERACTION");
        canonicalInteraction.setDeliverySourceDispatchId(700L);
        when(dispatchDao.findById(103L)).thenReturn(canonicalInteraction);

        DispatchDO out = service.enqueueInteractionRework(
                TENANT, 200L, 401L, 300L, 103L, 103L, null, 7L);

        assertEquals(103L, out.getResumeFromDispatchId());
        assertEquals(700L, out.getDeliverySourceDispatchId());
        assertEquals("COMMENT_REWORK", out.getResumeMode());
        verify(dispatchDao).insert(out);
    }

    @Test
    void returningWorkerHandoffPinsItsPriorProviderSession() {
        when(dispatchDao.findByIdempotencyKey(TENANT, "handoff:700")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(TENANT, 200L, 300L)).thenReturn(4);
        DispatchDO priorWorker = pending();
        priorWorker.setId(650L);
        priorWorker.setStatus(DispatchStatus.PAUSED);
        when(dispatchDao.listLatestByWorkitemAndAgent(TENANT, 200L, 400L, 20))
                .thenReturn(List.of(priorWorker));
        when(checkpointService.hasResumableSession(TENANT, 650L)).thenReturn(true);

        DispatchDO out = service.enqueueHandoff(TENANT, 200L, 300L, 400L, 700L, 7L);

        assertEquals(650L, out.getResumeFromDispatchId());
        assertEquals(700L, out.getDeliverySourceDispatchId());
        assertEquals("RETURNING_WORKER", out.getResumeMode());
    }

    @Test
    void handoffUsesCanonicalInteractionCreatedAfterSourceDispatch() {
        when(dispatchDao.findByIdempotencyKey(TENANT, "handoff:700")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(TENANT, 200L, 300L)).thenReturn(4);
        DispatchDO canonical = pending();
        canonical.setId(750L);
        canonical.setResumeMode("CANONICAL_INTERACTION");
        canonical.setExecutorId(19L);
        when(dispatchDao.listLatestByWorkitemAndAgent(TENANT, 200L, 400L, 20))
                .thenReturn(List.of(canonical));
        when(checkpointService.hasResumableSession(TENANT, 750L)).thenReturn(true);

        DispatchDO out = service.enqueueHandoff(TENANT, 200L, 300L, 400L, 700L, 7L);

        assertEquals(750L, out.getResumeFromDispatchId());
        assertEquals("RETURNING_WORKER", out.getResumeMode());
    }

    @Test
    void handoffFallsBackToOlderCanonicalDispatchWithDurableSession() {
        when(dispatchDao.findByIdempotencyKey(TENANT, "handoff:700")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(TENANT, 200L, 300L)).thenReturn(4);
        DispatchDO latestWithoutCheckpoint = pending();
        latestWithoutCheckpoint.setId(760L);
        DispatchDO canonical = pending();
        canonical.setId(750L);
        canonical.setResumeMode("CANONICAL_INTERACTION");
        when(dispatchDao.listLatestByWorkitemAndAgent(TENANT, 200L, 400L, 20))
                .thenReturn(List.of(latestWithoutCheckpoint, canonical));
        when(checkpointService.hasResumableSession(TENANT, 760L)).thenReturn(false);
        when(checkpointService.hasResumableSession(TENANT, 750L)).thenReturn(true);

        DispatchDO out = service.enqueueHandoff(TENANT, 200L, 300L, 400L, 700L, 7L);

        assertEquals(750L, out.getResumeFromDispatchId());
        assertEquals("RETURNING_WORKER", out.getResumeMode());
    }

    @Test
    void enqueueHandoffReturnsExistingForSameSource() {
        DispatchDO existing = pending();
        existing.setIdempotencyKey("handoff:700");
        when(dispatchDao.findByIdempotencyKey(TENANT, "handoff:700")).thenReturn(existing);

        DispatchDO out = service.enqueueHandoff(TENANT, 200L, 300L, 400L, 700L, 7L);

        assertSame(existing, out);
        verify(dispatchDao, never()).findMaxAttempt(anyLong(), anyLong(), anyLong());
        verify(dispatchDao, never()).insert(any());
    }

    @Test
    void automaticHandoffLimitAllowsFiveButStopsTheSixthDirectedHandoff() {
        when(dispatchDao.listByWorkitem(TENANT, 200L)).thenReturn(List.of(
                dispatch(1L, 10L, null, null),
                dispatch(2L, 20L, "handoff:1", null),
                dispatch(3L, 10L, "handoff:2", null),
                dispatch(4L, 20L, "handoff:3", null),
                dispatch(5L, 10L, "handoff:4", null),
                dispatch(6L, 20L, "handoff:5", null),
                dispatch(7L, 10L, "handoff:6", null),
                dispatch(8L, 20L, "handoff:7", null),
                dispatch(9L, 10L, "handoff:8", null),
                dispatch(10L, 20L, "handoff:9", null),
                dispatch(11L, 10L, "handoff:10", null)));

        assertTrue(service.hasReachedAutomaticHandoffLimit(TENANT, 200L, 11L, 20L, 5));
        assertFalse(service.hasReachedAutomaticHandoffLimit(TENANT, 200L, 9L, 20L, 5));
    }

    @Test
    void commentReworkResetsAutomaticHandoffLimit() {
        when(dispatchDao.listByWorkitem(TENANT, 200L)).thenReturn(List.of(
                dispatch(1L, 10L, null, null),
                dispatch(2L, 20L, "handoff:1", null),
                dispatch(3L, 10L, "handoff:2", null),
                dispatch(4L, 20L, "handoff:3", null),
                dispatch(5L, 10L, "handoff:4", null),
                dispatch(6L, 20L, "handoff:5", null),
                dispatch(7L, 10L, "handoff:6", "COMMENT_REWORK"),
                dispatch(8L, 20L, "handoff:7", null),
                dispatch(9L, 10L, "handoff:8", null)));

        assertFalse(service.hasReachedAutomaticHandoffLimit(TENANT, 200L, 9L, 20L, 5));
    }

    @Test
    void unrelatedWorkflowEdgesDoNotConsumeDirectedHandoffLimit() {
        when(dispatchDao.listByWorkitem(TENANT, 200L)).thenReturn(List.of(
                dispatch(1L, 10L, null, null),
                dispatch(2L, 30L, "handoff:1", null),
                dispatch(3L, 10L, "handoff:2", null),
                dispatch(4L, 20L, "handoff:3", null)));

        assertFalse(service.hasReachedAutomaticHandoffLimit(TENANT, 200L, 3L, 20L, 1));
    }

    private DispatchDO dispatch(long id, long agentId, String idempotencyKey, String resumeMode) {
        DispatchDO row = new DispatchDO();
        row.setId(id);
        row.setTenantId(TENANT);
        row.setWorkitemId(200L);
        row.setAgentId(agentId);
        row.setIdempotencyKey(idempotencyKey);
        row.setResumeMode(resumeMode);
        return row;
    }

    @Test
    void runPendingSkipsWhenLockNotAcquired() {
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(false);
        service.runPending(500L);
        verify(dispatchDao, never()).findById(anyLong());
    }

    @Test
    void runPendingSkipsWhenNotPending() {
        DispatchDO d = pending();
        d.setStatus(DispatchStatus.DISPATCHED);
        when(dispatchDao.findById(500L)).thenReturn(d);
        service.runPending(500L);
        verify(transport, never()).dispatch(any(), any());
        verify(redisManager).releaseLock(eq("dispatch:lock:500"), anyString());
    }

    @Test
    void runPendingKeepsPendingWhenAgentHasNoPublishedVersion() {
        DispatchDO d = pending();
        when(dispatchDao.findById(500L)).thenReturn(d);
        AgentDO a = onlineAgent();
        a.setStatus("OFFLINE");
        a.setOnlineVersionId(null);
        when(agentDao.findById(400L)).thenReturn(a);
        service.runPending(500L);
        verify(dispatchDao, never()).updateStatus(eq(500L), eq(TENANT), anyString(), any(), any(),
                any(), any(), any(), anyInt(), anyLong());
        verify(sdlcDriver, never()).onFail(anyLong(), anyLong(), anyLong());
        verify(redisManager).releaseLock(eq("dispatch:lock:500"), anyString());
    }

    @Test
    void runPendingKeepsPendingWhenNoExecutor() {
        DispatchDO d = pending();
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.select(400L)).thenReturn(null);
        service.runPending(500L);
        verify(dispatchDao, never()).updateStatus(eq(500L), eq(TENANT), anyString(), any(),
                any(), any(), any(), any(), anyInt(), anyLong());
        verify(sdlcDriver, never()).onFail(anyLong(), anyLong(), anyLong());
        verify(redisManager).releaseLock(eq("dispatch:lock:500"), anyString());
    }

    @Test
    void runPendingKeepsPendingWhenAgentCapacityLockIsBusy() {
        DispatchDO d = pending();
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(redisManager.tryAcquireLock(eq("dispatch:agent-capacity:400"), anyString(), anyLong()))
                .thenReturn(false);

        assertFalse(service.runPending(500L));

        verifyNoInteractions(executorSelector, assembler, taskPackager, transport);
        verify(redisManager).releaseLock(eq("dispatch:lock:500"), anyString());
    }

    @Test
    void runPendingDispatchesPublishedVersionWhileAgentEditIsPendingReview() {
        DispatchDO d = pending();
        when(dispatchDao.findById(500L)).thenReturn(d);
        AgentDO agent = onlineAgent();
        agent.setStatus("PENDING_REVIEW");
        when(agentDao.findById(400L)).thenReturn(agent);
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.select(400L)).thenReturn(9L);
        when(assembler.assemble(eq(d), any(AgentVersionDO.class))).thenReturn(new PackageContext());
        TaskPackageResult pkg = new TaskPackageResult("oss://b/500.zip", "md5", 10L, "http://dl", "deadbeef");
        when(taskPackager.build(any())).thenReturn(pkg);

        assertTrue(service.runPending(500L));

        verify(transport).dispatch(eq(d), eq(pkg));
    }

    @Test
    void runPendingHappyPathDispatches() {
        DispatchDO d = pending();
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.select(400L)).thenReturn(9L);
        when(assembler.assemble(eq(d), any(AgentVersionDO.class))).thenReturn(new PackageContext());
        TaskPackageResult pkg = new TaskPackageResult("oss://b/500.zip", "md5", 10L, "http://dl", "deadbeef");
        when(taskPackager.build(any())).thenReturn(pkg);

        assertTrue(service.runPending(500L));

        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.PACKAGING),
                eq(410L), eq(9L), isNull(), isNull(), isNull(), anyInt(), anyLong());
        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.DISPATCHED),
                any(), eq(9L), eq("oss://b/500.zip"), isNull(), isNull(), anyInt(), anyLong());
        verify(transport).dispatch(eq(d), eq(pkg));
        verify(redisManager).releaseLock(eq("dispatch:agent-capacity:400"), anyString());
        verify(redisManager).releaseLock(eq("dispatch:lock:500"), anyString());
    }

    @Test
    void scheduledRunPackagesTheFrozenVersionAfterOnlineVersionAdvances() {
        DispatchDO d = pending();
        d.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        d.setAgentVersionId(410L);
        d.setAgentVersionId(401L);
        AgentVersionDO frozen = onlineVersion(); frozen.setId(401L);
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(agentDao.findById(400L)).thenReturn(onlineAgent()); // current online version is 410
        when(agentVersionDao.findById(401L)).thenReturn(frozen);
        when(executorSelector.select(400L)).thenReturn(9L);
        when(assembler.assemble(eq(d), same(frozen))).thenReturn(new PackageContext());
        TaskPackageResult pkg = new TaskPackageResult("oss://b/500.zip", "md5", 10L, "http://dl", "deadbeef");
        when(taskPackager.build(any())).thenReturn(pkg);

        assertTrue(service.runPending(500L));

        verify(agentVersionDao).findById(401L);
        verify(agentVersionDao, never()).findById(410L);
        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.PACKAGING),
                eq(401L), eq(9L), isNull(), isNull(), isNull(), anyInt(), anyLong());
    }

    @Test
    void returningWorkerPrefersExecutorThatOwnsCanonicalSession() {
        DispatchDO d = pending();
        d.setResumeMode("RETURNING_WORKER");
        d.setResumeFromDispatchId(750L);
        DispatchDO canonical = pending();
        canonical.setId(750L);
        canonical.setExecutorId(19L);
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(dispatchDao.findById(750L)).thenReturn(canonical);
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.select(400L, 19L)).thenReturn(19L);
        when(assembler.assemble(eq(d), any(AgentVersionDO.class))).thenReturn(new PackageContext());
        TaskPackageResult pkg = new TaskPackageResult("oss://b/500.zip", "md5", 10L, "http://dl", "deadbeef");
        when(taskPackager.build(any())).thenReturn(pkg);

        assertTrue(service.runPending(500L));

        verify(executorSelector).select(400L, 19L);
        verify(executorSelector, never()).select(400L);
        verify(transport).dispatch(d, pkg);
    }

    @Test
    void continuousSessionNeverFallsBackWhenSourceExecutorDropsAfterPlanning() {
        DispatchDO d = pending();
        d.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        d.setAgentVersionId(410L); d.setResumeMode("CONTINUOUS"); d.setResumeFromDispatchId(750L);
        DispatchDO source = pending(); source.setId(750L); source.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        source.setWorkitemId(499L); source.setExecutorId(19L);
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(dispatchDao.findById(750L)).thenReturn(source);
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.selectStrict(400L, 19L)).thenReturn(null);

        assertFalse(service.runPending(500L));

        verify(executorSelector).selectStrict(400L, 19L);
        verify(executorSelector, never()).select(400L);
        verify(executorSelector, never()).select(400L, 19L);
    }

    @Test
    void degradedContinuousResumeDoesNotReuseFencedNativeDispatch() {
        DispatchDO canceledNative = pending(); canceledNative.setStatus(DispatchStatus.CANCELED);
        when(dispatchDao.findByIdempotencyKey(TENANT, "scheduled-resume:77:900:native"))
                .thenReturn(canceledNative);
        when(dispatchDao.findByIdempotencyKey(TENANT, "scheduled-resume:77:900:degraded"))
                .thenReturn(null);

        DispatchDO created = service.enqueueScheduledResume(TENANT, 77L, 301L, 400L,
                2, 900L, true, 0L);

        assertEquals("DEGRADED_CONTINUOUS", created.getResumeMode());
        verify(dispatchDao).insert(argThat(row -> "scheduled-resume:77:900:degraded".equals(row.getIdempotencyKey())
                && "DEGRADED_CONTINUOUS".equals(row.getResumeMode())
                && Long.valueOf(900L).equals(row.getResumeFromDispatchId())));
        verify(dispatchDao, never()).findByIdempotencyKey(TENANT, "scheduled-resume:77:900:native");
    }

    @Test
    void continuousFenceRefusesDegradedReplacementWhenNativeDispatchAdvanced() {
        DispatchDO nativeDispatch = pending(); nativeDispatch.setResumeMode("CONTINUOUS");
        when(dispatchDao.listBySource(TENANT, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 77L))
                .thenReturn(java.util.List.of(nativeDispatch));
        when(dispatchDao.findById(500L)).thenReturn(nativeDispatch);
        nativeDispatch.setStatus(DispatchStatus.PACKAGING);

        assertFalse(service.fencePendingContinuousResume(TENANT, 77L));
    }

    @Test
    void resumeAffinityNeverCrossesExecutionSourcesWithEqualNumericSubjectIds() {
        DispatchDO scheduled = pending();
        scheduled.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        scheduled.setAgentVersionId(410L);
        scheduled.setResumeMode("RETURNING_WORKER");
        scheduled.setResumeFromDispatchId(750L);
        DispatchDO workitem = pending();
        workitem.setId(750L);
        workitem.setSourceType(ExecutionSourceType.WORKITEM.name());
        workitem.setExecutorId(19L);
        when(dispatchDao.findById(500L)).thenReturn(scheduled);
        when(dispatchDao.findById(750L)).thenReturn(workitem);
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.select(400L)).thenReturn(9L);
        when(assembler.assemble(eq(scheduled), any(AgentVersionDO.class))).thenReturn(new PackageContext());
        TaskPackageResult pkg = new TaskPackageResult("oss://b/500.zip", "md5", 10L, "http://dl", "deadbeef");
        when(taskPackager.build(any())).thenReturn(pkg);

        assertTrue(service.runPending(500L));

        verify(executorSelector).select(400L);
        verify(executorSelector, never()).select(400L, 19L);
    }

    @Test
    void sideInteractionPrefersExecutorThatOwnsSourceSession() {
        assertInteractionPrefersSourceExecutor("SIDE_INTERACTION");
    }

    @Test
    void sideInteractionDegradesToCanonicalWhenSelectorCannotUseSourceExecutor() {
        DispatchDO d = pending();
        d.setResumeMode("SIDE_INTERACTION");
        d.setResumeFromDispatchId(750L);
        DispatchDO source = pending();
        source.setId(750L);
        source.setExecutorId(19L);
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(dispatchDao.findById(750L)).thenReturn(source);
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.selectForInteraction(400L, 19L)).thenReturn(9L);

        when(assembler.assemble(eq(d), any(AgentVersionDO.class))).thenReturn(new PackageContext());
        TaskPackageResult pkg = new TaskPackageResult("oss://b/500.zip", "md5", 10L, "http://dl", "deadbeef");
        when(taskPackager.build(any())).thenReturn(pkg);

        assertTrue(service.runPending(500L));

        verify(executorSelector).selectForInteraction(400L, 19L);
        assertEquals("CANONICAL_INTERACTION", d.getResumeMode());
        verify(transport).dispatch(d, pkg);
    }

    @Test
    void canonicalInteractionPrefersExecutorThatOwnsSourceSession() {
        assertInteractionPrefersSourceExecutor("CANONICAL_INTERACTION");
    }

    private void assertInteractionPrefersSourceExecutor(String resumeMode) {
        DispatchDO d = pending();
        d.setResumeMode(resumeMode);
        d.setResumeFromDispatchId(750L);
        DispatchDO source = pending();
        source.setId(750L);
        source.setExecutorId(19L);
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(dispatchDao.findById(750L)).thenReturn(source);
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.selectForInteraction(400L, 19L)).thenReturn(19L);
        when(assembler.assemble(eq(d), any(AgentVersionDO.class))).thenReturn(new PackageContext());
        TaskPackageResult pkg = new TaskPackageResult("oss://b/500.zip", "md5", 10L, "http://dl", "deadbeef");
        when(taskPackager.build(any())).thenReturn(pkg);

        assertTrue(service.runPending(500L));

        verify(executorSelector).selectForInteraction(400L, 19L);
        verify(executorSelector, never()).select(400L);
    }

    @Test
    void runPendingReturnsDispatchToPendingWhenWebSocketSendFails() {
        DispatchDO d = pending();
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.select(400L)).thenReturn(9L);
        when(assembler.assemble(eq(d), any(AgentVersionDO.class))).thenReturn(new PackageContext());
        TaskPackageResult pkg = new TaskPackageResult("oss://b/500.zip", "md5", 10L,
                "http://dl", "deadbeef");
        when(taskPackager.build(any())).thenReturn(pkg);
        doThrow(new IllegalStateException("TEXT_FULL_WRITING"))
                .when(transport).dispatch(d, pkg);
        when(dispatchDao.returnDispatchedToPending(500L, TENANT, 9L, 2, 0L)).thenReturn(1);

        assertFalse(service.runPending(500L));

        verify(dispatchDao).returnDispatchedToPending(500L, TENANT, 9L, 2, 0L);
        verify(sdlcDriver, never()).onFail(anyLong(), anyLong(), anyLong());
    }

    @Test
    void runPendingReturnsPackagingToPendingWhenPackageBuildFails() {
        DispatchDO d = pending();
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.select(400L)).thenReturn(9L);
        when(assembler.assemble(eq(d), any(AgentVersionDO.class))).thenReturn(new PackageContext());
        when(taskPackager.build(any())).thenThrow(new IllegalStateException("OSS unavailable"));
        when(dispatchDao.returnPackagingToPending(500L, TENANT, 1, 0L)).thenReturn(1);

        assertFalse(service.runPending(500L));

        verify(dispatchDao).returnPackagingToPending(500L, TENANT, 1, 0L);
        verify(transport, never()).dispatch(any(), any());
        verify(sdlcDriver, never()).onFail(anyLong(), anyLong(), anyLong());
    }

    @Test
    void runPendingFailsPermanentlyWhenTaskPackageBucketDoesNotExist() {
        DispatchDO d = pending();
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.select(400L)).thenReturn(9L);
        when(assembler.assemble(eq(d), any(AgentVersionDO.class))).thenReturn(new PackageContext());
        when(taskPackager.build(any())).thenThrow(new ObjectStorageException(
                "oss put failed",
                "autowonder-task-pkg-daily-tmp",
                "10002/12629/10832.zip",
                "NoSuchBucket",
                new RuntimeException("bucket is missing")));

        assertFalse(service.runPending(500L));

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.FAILED),
                isNull(), isNull(), isNull(), isNull(), errorCaptor.capture(),
                eq(1), eq(0L));
        assertTrue(errorCaptor.getValue().contains("NoSuchBucket"));
        assertTrue(errorCaptor.getValue().contains("autowonder-task-pkg-daily-tmp"));
        verify(dispatchDao, never()).returnPackagingToPending(anyLong(), anyLong(), anyInt(), anyLong());
        verify(transport, never()).dispatch(any(), any());
        verify(sdlcDriver).onFail(TENANT, 200L, 300L);
    }

    @Test
    void runPendingFailsPermanentlyWhenPackageInputIsInvalid() {
        DispatchDO d = pending();
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.select(400L)).thenReturn(9L);
        when(assembler.assemble(eq(d), any(AgentVersionDO.class))).thenReturn(new PackageContext());
        when(taskPackager.build(any())).thenThrow(new BizException(
                ErrorCode.PACKAGE_BUILD_FAILED,
                new IllegalArgumentException("plugin providers are required: release-tools")));

        assertFalse(service.runPending(500L));

        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.FAILED),
                isNull(), isNull(), isNull(), isNull(),
                argThat(error -> error.contains("plugin providers are required: release-tools")),
                eq(1), eq(0L));
        verify(dispatchDao, never()).returnPackagingToPending(anyLong(), anyLong(), anyInt(), anyLong());
        verify(transport, never()).dispatch(any(), any());
        verify(sdlcDriver).onFail(TENANT, 200L, 300L);
    }

    @Test
    void runPendingFailsPermanentlyWhenBoundCapabilityIsMissing() {
        DispatchDO d = pending();
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.select(400L)).thenReturn(9L);
        when(assembler.assemble(eq(d), any(AgentVersionDO.class))).thenThrow(
                new IllegalStateException("bound capability is missing or belongs to another tenant: 42"));

        assertFalse(service.runPending(500L));

        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.FAILED),
                isNull(), isNull(), isNull(), isNull(),
                argThat(error -> error.contains("bound capability is missing")),
                eq(1), eq(0L));
        verify(dispatchDao, never()).returnPackagingToPending(anyLong(), anyLong(), anyInt(), anyLong());
        verify(sdlcDriver).onFail(TENANT, 200L, 300L);
    }

    @Test
    void runPendingFailsPermanentlyWhenScheduledSnapshotIsInvalid() {
        DispatchDO d = pending();
        d.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        d.setAgentVersionId(410L);
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.select(400L)).thenReturn(9L);
        when(assembler.assemble(eq(d), any(AgentVersionDO.class))).thenThrow(
                new BizException(ErrorCode.SCHEDULED_TASK_INVALID_STATE, "snapshot agent context missing"));

        assertFalse(service.runPending(500L));

        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.FAILED),
                isNull(), isNull(), isNull(), isNull(),
                argThat(error -> error.contains("snapshot agent context missing")),
                eq(1), eq(0L));
        verify(dispatchDao, never()).returnPackagingToPending(anyLong(), anyLong(), anyInt(), anyLong());
        verify(sdlcDriver, never()).onFail(TENANT, 200L, 300L);
    }

    @Test
    void runPendingRequeuesUnclassifiedBusinessFailure() {
        DispatchDO d = pending();
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.select(400L)).thenReturn(9L);
        when(assembler.assemble(eq(d), any(AgentVersionDO.class))).thenThrow(
                new BizException(ErrorCode.NOT_FOUND, "temporary dependent row unavailable"));
        when(dispatchDao.returnPackagingToPending(500L, TENANT, 1, 0L)).thenReturn(1);

        assertFalse(service.runPending(500L));

        verify(dispatchDao).returnPackagingToPending(500L, TENANT, 1, 0L);
        verify(dispatchDao, never()).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.FAILED),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
        verify(sdlcDriver, never()).onFail(anyLong(), anyLong(), anyLong());
    }

    @Test
    void runPendingSurfacesActionableMissingReworkCommentInsteadOfRequeueing() {
        DispatchDO d = pending();
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.select(400L)).thenReturn(9L);
        when(assembler.assemble(eq(d), any(AgentVersionDO.class))).thenThrow(
                new IllegalStateException("COMMENT_REWORK_CONTEXT_MISSING: trigger comment is missing; "
                        + "dispatchId=500; 请重新发送 @ 评论发起返工"));

        assertFalse(service.runPending(500L));

        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.FAILED),
                isNull(), isNull(), isNull(), isNull(),
                argThat(error -> error.contains("请重新发送 @ 评论")
                        && error.contains("dispatchId=500")),
                eq(1), eq(0L));
        verify(dispatchDao, never()).returnPackagingToPending(anyLong(), anyLong(), anyInt(), anyLong());
        verify(transport, never()).dispatch(any(), any());
        verify(sdlcDriver).onFail(TENANT, 200L, 300L);
    }

    @Test
    void drainPendingRunsOldestRowsUntilCapacityIsUnavailable() {
        DispatchDO first = pending();
        DispatchDO second = pending();
        second.setId(501L);
        when(dispatchDao.listOldestPendingByAgent(400L, 1))
                .thenReturn(java.util.List.of(first), java.util.List.of(second));
        DispatchService spy = spy(service);
        doReturn(true).when(spy).runPending(500L);
        doReturn(false).when(spy).runPending(501L);

        spy.drainPending(400L);

        verify(spy).runPending(500L);
        verify(spy).runPending(501L);
    }
}
