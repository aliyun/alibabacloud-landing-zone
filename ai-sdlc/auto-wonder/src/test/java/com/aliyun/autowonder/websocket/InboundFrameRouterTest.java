package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.artifact.ArtifactService;
import com.aliyun.autowonder.artifact.ArtifactOwnerRef;
import com.aliyun.autowonder.artifact.dto.ReportArtifactRequest;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.HandoffResult;
import com.aliyun.autowonder.dispatch.HandoffService;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.executor.ExecutorDispatchSnapshot;
import com.aliyun.autowonder.executor.ExecutorService;
import com.aliyun.autowonder.executor.ProviderModelCatalogService;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.guidance.InteractionWorkflowService;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;

import javax.websocket.Session;
import javax.websocket.RemoteEndpoint;

import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InboundFrameRouterTest {

    private DispatchService dispatchService;
    private ArtifactService artifactService;
    private PresenceManager presenceManager;
    private HandoffService handoffService;
    private DispatchDrainScheduler drainScheduler;
    private DispatchPauseService pauseService;
    private GuidanceService guidanceService;
    private InboundFrameRouter router;
    private ScheduledTaskCapabilityGuard capabilityGuard;

    @Test
    void canceledResultOnlyConfirmsStopAndNeverRoutesWorkflowOrArtifacts() {
        var recovery = mock(com.aliyun.autowonder.dispatch.DispatchRecoveryService.class);
        router.setRecovery(recovery);
        when(recovery.cancelRequested(100L, 55L)).thenReturn(true);
        when(recovery.onStopped(100L, 1L, 55L)).thenReturn(true);
        router.route(session(1L, 10L, 100L), "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true,\"workflowPlan\":{}}");
        verify(recovery).onStopped(100L, 1L, 55L);
        verifyNoInteractions(dispatchService, handoffService, artifactService);
    }

    @Test
    void canceledGuidanceWithoutDispatchIdCannotApplyLateWorkflowPlan() {
        var recovery = mock(com.aliyun.autowonder.dispatch.DispatchRecoveryService.class);
        router.setRecovery(recovery);
        when(recovery.cancelRequested(100L, 42L)).thenReturn(true);
        router.route(session(1L, 10L, 100L), "{\"type\":\"TASK_GUIDANCE_ACK\",\"guidanceId\":77,\"status\":\"APPLIED\",\"workflowPlan\":{}}");
        verify(guidanceService, never()).acknowledge(anyLong(), anyLong(), anyLong(), anyString(), any(), any());
        verifyNoInteractions(handoffService, artifactService);
    }

    @BeforeEach
    void setUp() {
        dispatchService = mock(DispatchService.class);
        artifactService = mock(ArtifactService.class);
        presenceManager = mock(PresenceManager.class);
        handoffService = mock(HandoffService.class);
        drainScheduler = mock(DispatchDrainScheduler.class);
        pauseService = mock(DispatchPauseService.class);
        guidanceService = mock(GuidanceService.class);
        when(guidanceService.bindingForInboundAcknowledgement(100L, 1L, 77L))
                .thenReturn(new GuidanceService.InboundAcknowledgementBinding(42L,
                        new ArtifactOwnerRef(ExecutionSourceType.WORKITEM, 200L)));
        capabilityGuard = mock(ScheduledTaskCapabilityGuard.class);
        router = new InboundFrameRouter(dispatchService, artifactService, presenceManager,
                handoffService, drainScheduler, pauseService, guidanceService);
        ReflectionTestUtils.setField(router, "capabilityGuard", capabilityGuard);
        when(presenceManager.publishHeartbeat(anyLong(), anyLong(), any(), any(), anyCollection(),
                any(), any())).thenReturn(PresenceManager.SessionMutationResult.APPLIED);
    }

    private String heartbeat(Object... fields) {
        JSONObject json = new JSONObject();
        json.put("type", "HEARTBEAT");
        json.put("maxConcurrentDispatches", 1);
        json.put("protocolFeatures", java.util.List.of("dispatch_inventory_v1"));
        json.put("runningDispatchIds", java.util.List.of());
        json.put("ownedDispatchIds", java.util.List.of());
        json.put("runningConversationTurnIds", java.util.List.of());
        json.put("dispatchInventoryReady", true);
        boolean ownedExplicit = false;
        for (int i = 0; i < fields.length; i += 2) {
            String key = (String) fields[i];
            json.put(key, fields[i + 1]);
            if ("ownedDispatchIds".equals(key)) ownedExplicit = true;
        }
        if (!ownedExplicit && json.get("runningDispatchIds") instanceof java.util.Collection<?>) {
            json.put("ownedDispatchIds", json.get("runningDispatchIds"));
        }
        return json.toJSONString();
    }

    private ExecutorSession session(long executorId, long agentId, long tenantId) {
        return new ExecutorSession(executorId, agentId, tenantId, mock(Session.class));
    }

    private ExecutorSession sessionWithBasic(long executorId, long agentId, long tenantId,
            RemoteEndpoint.Basic basicRemote) {
        Session session = mock(Session.class);
        when(session.getBasicRemote()).thenReturn(basicRemote);
        return new ExecutorSession(executorId, agentId, tenantId, session);
    }

    private LeaseRoutingFixture leaseRoutingFixture() {
        RedisManager redisManager = mock(RedisManager.class);
        java.util.Map<Object, Object> values = new java.util.HashMap<>();
        when(redisManager.get(any())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(1));
            return true;
        }).when(redisManager).set(any(), any(), anyInt());
        doAnswer(invocation -> {
            values.remove(invocation.getArgument(0));
            return 1L;
        }).when(redisManager).del(any());

        ExecutorRegistry registry = spy(new ExecutorRegistry(redisManager));
        DispatchService service = new DispatchService(null, null, null, null, null, null, null,
                null, null, null, null, null, null, registry);
        InboundFrameRouter registryRouter = new InboundFrameRouter(service, artifactService,
                presenceManager, handoffService, drainScheduler, pauseService, guidanceService);
        return new LeaseRoutingFixture(registryRouter, registry);
    }

    private record LeaseRoutingFixture(InboundFrameRouter router, ExecutorRegistry registry) {
    }

    @Test
    void taskResultReplyUsesTheSameSerializedBasicWriterAsDispatchFrames() throws Exception {
        RemoteEndpoint.Async asyncRemote = mock(RemoteEndpoint.Async.class);
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        Session ws = mock(Session.class);
        when(ws.getAsyncRemote()).thenReturn(asyncRemote);
        when(ws.getBasicRemote()).thenReturn(basicRemote);
        when(ws.isOpen()).thenReturn(true);
        when(dispatchService.hasDurableCheckpoint(100L, 55L, 7L, "sha256:abc")).thenReturn(true);
        when(dispatchService.onResult(100L, 1L, 55L, true, "done", null, false, false, null)).thenReturn(true);

        router.route(new ExecutorSession(1L, 10L, 100L, ws),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true,\"resultSummary\":\"done\"," +
                        "\"checkpointReceiptVersion\":1,\"checkpointSeq\":7,\"checkpointSha256\":\"sha256:abc\"}");

        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")
                && text.contains("55")));
        verify(asyncRemote, never()).sendText(anyString());
    }

    @Test
    void heartbeatWithoutInventoryProtocolRemainsOnlineWithoutAuthoritativeReconciliation() throws Exception {
        Session ws = mock(Session.class);
        when(ws.getId()).thenReturn("legacy-session");
        var reconciler = mock(com.aliyun.autowonder.dispatch.DispatchRuntimeReconciler.class);
        router.setRuntimeReconciler(reconciler);

        router.route(new ExecutorSession(1L, 10L, 100L, 10, ws),
                "{\"type\":\"HEARTBEAT\",\"maxConcurrentDispatches\":10,"
                        + "\"runningDispatchIds\":[55,56],\"runningConversationTurnIds\":[77]} ");

        ArgumentCaptor<ExecutorDispatchSnapshot> snapshot = ArgumentCaptor.forClass(ExecutorDispatchSnapshot.class);
        verify(presenceManager).publishHeartbeat(eq(1L), eq(10L), eq("legacy-session"),
                snapshot.capture(), isNull(), isNull(), isNull());
        assertFalse(snapshot.getValue().inventoryReady());
        assertEquals(java.util.Set.of(55L, 56L), snapshot.getValue().runningDispatchIds());
        verify(dispatchService).renewActiveLeases(100L, 1L, java.util.List.of(55L, 56L));
        verify(drainScheduler).request(10L);
        verify(reconciler, never()).request(anyLong(), anyLong(), anyLong(), anyString());
        verify(ws, never()).close();
    }

    @Test
    void legacyHeartbeatWithoutConversationReportPreservesUnknownActivity() {
        router.route(session(1L, 10L, 100L), "{\"type\":\"HEARTBEAT\"}");
        ArgumentCaptor<ExecutorDispatchSnapshot> snapshot = ArgumentCaptor.forClass(ExecutorDispatchSnapshot.class);
        verify(presenceManager).publishHeartbeat(eq(1L), eq(10L), isNull(),
                snapshot.capture(), isNull(), isNull(), isNull());
        assertFalse(snapshot.getValue().hasConversationActivityReport());
    }

    @Test
    void legacyHeartbeatWithEmptyConversationReportPreservesKnownIdleActivity() {
        router.route(session(1L, 10L, 100L),
                "{\"type\":\"HEARTBEAT\",\"runningConversationTurnIds\":[]}");
        ArgumentCaptor<ExecutorDispatchSnapshot> snapshot = ArgumentCaptor.forClass(ExecutorDispatchSnapshot.class);
        verify(presenceManager).publishHeartbeat(eq(1L), eq(10L), isNull(),
                snapshot.capture(), isNull(), isNull(), isNull());
        assertTrue(snapshot.getValue().hasConversationActivityReport());
        assertTrue(snapshot.getValue().runningConversationTurnIds().isEmpty());
    }

    @Test
    void heartbeatRoutesProtocolFeaturesWithoutRequiringActivityReport() {
        router.route(session(1L, 10L, 100L),
                heartbeat("protocolFeatures", java.util.List.of(
                        "dispatch_inventory_v1", "AGENT_ENVIRONMENT_VARIABLES_V1")));

        verify(presenceManager).publishHeartbeat(eq(1L), eq(10L), isNull(), any(),
                eq(java.util.List.of("dispatch_inventory_v1", "AGENT_ENVIRONMENT_VARIABLES_V1")),
                isNull(), isNull());
    }

    @Test
    void inventoryHeartbeatStoresCurrentSessionSnapshotAndRefreshesCapacity() {
        Session ws = mock(Session.class);
        when(ws.getId()).thenReturn("session-1");
        ExecutorSession es = new ExecutorSession(1L, 10L, 100L, 1, ws);

        router.route(es, "{\"type\":\"HEARTBEAT\",\"maxConcurrentDispatches\":10,"
                + "\"protocolFeatures\":[\"dispatch_inventory_v1\"],"
                + "\"runningDispatchIds\":[55],\"ownedDispatchIds\":[55,56],"
                + "\"runningConversationTurnIds\":[77],\"dispatchInventoryReady\":true}");

        ArgumentCaptor<ExecutorDispatchSnapshot> snapshot = ArgumentCaptor.forClass(ExecutorDispatchSnapshot.class);
        verify(presenceManager).publishHeartbeat(eq(1L), eq(10L), eq("session-1"),
                snapshot.capture(), eq(java.util.List.of("dispatch_inventory_v1")), isNull(), isNull());
        assertEquals("session-1", snapshot.getValue().sessionId());
        assertEquals(10, snapshot.getValue().capacity());
        assertEquals(java.util.Set.of(55L), snapshot.getValue().runningDispatchIds());
        assertEquals(java.util.Set.of(55L, 56L), snapshot.getValue().ownedDispatchIds());
        assertTrue(snapshot.getValue().inventoryReady());
        verify(dispatchService).renewActiveLeases(100L, 1L, java.util.List.of(55L));
    }

    @Test
    void staleSessionHeartbeatIsClosedBeforeAnyDownstreamEffects() throws Exception {
        Session ws = mock(Session.class);
        when(ws.getId()).thenReturn("session-old");
        when(presenceManager.publishHeartbeat(anyLong(), anyLong(), any(), any(), anyCollection(),
                any(), any())).thenReturn(PresenceManager.SessionMutationResult.STALE_SESSION);

        router.route(new ExecutorSession(1L, 10L, 100L, 1, ws), heartbeat());

        verify(ws).close();
        verify(dispatchService, never()).renewActiveLeases(anyLong(), anyLong(), any());
        verify(drainScheduler, never()).request(anyLong());
    }

    @Test
    void readyInventoryRequestsSessionFencedReconciliationInsteadOfDirectDrain() {
        Session ws = mock(Session.class);
        when(ws.getId()).thenReturn("session-1");
        var reconciler = mock(com.aliyun.autowonder.dispatch.DispatchRuntimeReconciler.class);
        router.setRuntimeReconciler(reconciler);

        router.route(new ExecutorSession(1L, 10L, 100L, 1, ws),
                "{\"type\":\"HEARTBEAT\",\"maxConcurrentDispatches\":10,"
                        + "\"protocolFeatures\":[\"dispatch_inventory_v1\"],"
                        + "\"runningDispatchIds\":[],\"ownedDispatchIds\":[],"
                        + "\"runningConversationTurnIds\":[],\"dispatchInventoryReady\":true}");

        verify(reconciler).request(100L, 1L, 10L, "session-1");
        verify(drainScheduler, never()).request(anyLong());
    }

    @Test
    void completeInventoryRejectsRunningDispatchMissingFromOwnedSet() throws Exception {
        Session ws = mock(Session.class);
        when(ws.getId()).thenReturn("session-1");
        ExecutorSession es = new ExecutorSession(1L, 10L, 100L, 1, ws);

        router.route(es, "{\"type\":\"HEARTBEAT\",\"maxConcurrentDispatches\":10,"
                + "\"protocolFeatures\":[\"dispatch_inventory_v1\"],"
                + "\"runningDispatchIds\":[55],\"ownedDispatchIds\":[],"
                + "\"runningConversationTurnIds\":[],\"dispatchInventoryReady\":true}");

        verify(ws).close();
        verify(presenceManager, never()).publishHeartbeat(anyLong(), anyLong(), any(), any(),
                anyCollection(), any(), any());
        verify(dispatchService, never()).renewActiveLeases(anyLong(), anyLong(), any());
    }

    @Test
    void heartbeatRecordsReportedRuntimeVersion() {
        router.route(session(1L, 10L, 100L),
                heartbeat("version", "0.2.152"));

        verify(presenceManager).publishHeartbeat(eq(1L), eq(10L), isNull(), any(),
                anyCollection(), eq("0.2.152"), isNull());
    }

    @Test
    void heartbeatWithoutVersionPassesNullThroughToPresence() {
        router.route(session(1L, 10L, 100L), heartbeat());

        verify(presenceManager).publishHeartbeat(eq(1L), eq(10L), isNull(), any(),
                anyCollection(), isNull(), isNull());
    }

    @Test
    void heartbeatRejectedByTombstoneSkipsVersionRecording() {
        when(presenceManager.publishHeartbeat(eq(1L), eq(10L), any(), any(), anyCollection(),
                any(), any())).thenReturn(PresenceManager.SessionMutationResult.DELETED);

        router.route(session(1L, 10L, 100L),
                heartbeat("version", "0.2.152"));

        verify(presenceManager).publishHeartbeat(eq(1L), eq(10L), any(), any(),
                anyCollection(), eq("0.2.152"), isNull());
    }

    @Test
    void heartbeatRecordsReportedModel() {
        router.route(session(1L, 10L, 100L),
                heartbeat("model", "qoder3-coder-plus"));

        verify(presenceManager).publishHeartbeat(eq(1L), eq(10L), isNull(), any(),
                anyCollection(), isNull(), eq("qoder3-coder-plus"));
    }

    @Test
    void heartbeatWithoutModelPassesNullThroughToPresence() {
        router.route(session(1L, 10L, 100L), heartbeat());

        verify(presenceManager).publishHeartbeat(eq(1L), eq(10L), isNull(), any(),
                anyCollection(), isNull(), isNull());
    }

    @Test
    void heartbeatRejectedByTombstoneSkipsModelRecording() {
        when(presenceManager.publishHeartbeat(eq(1L), eq(10L), any(), any(), anyCollection(),
                any(), any())).thenReturn(PresenceManager.SessionMutationResult.DELETED);

        router.route(session(1L, 10L, 100L),
                heartbeat("model", "qoder3-coder-plus"));

        verify(presenceManager).publishHeartbeat(eq(1L), eq(10L), any(), any(),
                anyCollection(), isNull(), eq("qoder3-coder-plus"));
    }

    @Test
    void heartbeatWithExplicitEmptyRunningDispatchIdsPassesKnownEmptyLease() {
        router.route(session(1L, 10L, 100L),
                heartbeat("runningDispatchIds", java.util.List.of()));

        verify(dispatchService).renewActiveLeases(100L, 1L, java.util.List.of());
    }

    @Test
    void heartbeatWithStringifiedRunningDispatchIdsIsRejected() throws Exception {
        Session ws = mock(Session.class);
        router.route(new ExecutorSession(1L, 10L, 100L, ws),
                heartbeat("runningDispatchIds", "[]"));
        verify(ws).close();
        verify(presenceManager).recordProtocolError(eq(1L), eq(10L), isNull(), startsWith("EXECUTOR_PROTOCOL_INCOMPATIBLE:"));
    }

    @Test
    void heartbeatWithNonnumericRunningDispatchIdIsRejected() throws Exception {
        Session ws = mock(Session.class);
        router.route(new ExecutorSession(1L, 10L, 100L, ws),
                heartbeat("runningDispatchIds", java.util.List.of("not-a-number")));
        verify(ws).close();
        verify(presenceManager).recordProtocolError(eq(1L), eq(10L), isNull(), startsWith("EXECUTOR_PROTOCOL_INCOMPATIBLE:"));
    }

    @Test
    void heartbeatRejectedByTombstoneClosesSessionAndSkipsDownstream() throws Exception {
        Session ws = mock(Session.class);
        ExecutorSession es = new ExecutorSession(1L, 10L, 100L, ws);
        when(presenceManager.publishHeartbeat(eq(1L), eq(10L), any(), any(), anyCollection(),
                any(), any())).thenReturn(PresenceManager.SessionMutationResult.DELETED);

        router.route(es, heartbeat());

        verify(ws).close();
        verify(dispatchService, never()).renewActiveLeases(anyLong(), anyLong(), any());
        verify(drainScheduler, never()).request(anyLong());
    }

    @Test
    void taskAckCallsOnAck() {
        router.route(session(1L, 10L, 100L), "{\"type\":\"TASK_ACK\",\"dispatchId\":55}");
        verify(dispatchService).onAck(100L, 55L);
        verify(guidanceService).deliverQueuedForDispatch(100L, 55L);
    }

    @Test
    void taskPausedRequiresDurableCheckpointReceipt() {
        when(pauseService.onPaused(100L, 1L, 55L, 42L, "sha256:abc")).thenReturn(true);
        router.route(session(1L, 10L, 100L),
                "{\"type\":\"TASK_PAUSED\",\"dispatchId\":55," +
                        "\"checkpointSeq\":42,\"checkpointSha256\":\"sha256:abc\"}");
        verify(pauseService).onPaused(100L, 1L, 55L, 42L, "sha256:abc");
        verify(guidanceService).requeueDeliveredForDispatch(100L, 55L);
    }

    @Test
    void taskPauseFailedRecordsFailure() {
        router.route(session(1L, 10L, 100L),
                "{\"type\":\"TASK_PAUSE_FAILED\",\"dispatchId\":55," +
                        "\"error\":\"checkpoint upload failed\"}");
        verify(pauseService).onPauseFailed(100L, 1L, 55L, "checkpoint upload failed");
    }

    @Test
    void guidanceAckUpdatesDurableGuidance() {
        router.route(session(1L, 10L, 100L),
                "{\"type\":\"TASK_GUIDANCE_ACK\",\"guidanceId\":77,\"status\":\"APPLIED\"}");
        verify(guidanceService).acknowledge(100L, 1L, 77L, "APPLIED", null, null);
    }

    @Test
    void scheduledGuidanceAckWithoutDispatchIdFailsBeforeAcknowledgementWhenUnavailable() {
        when(guidanceService.bindingForInboundAcknowledgement(100L, 1L, 77L))
                .thenReturn(new GuidanceService.InboundAcknowledgementBinding(88L,
                        new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK_RUN, 200L)));
        doThrow(new BizException(ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY))
                .when(capabilityGuard).requireAvailable("daemon");

        BizException failure = assertThrows(BizException.class, () -> router.route(
                session(1L, 10L, 100L),
                "{\"type\":\"TASK_GUIDANCE_ACK\",\"guidanceId\":77,\"status\":\"APPLIED\"}"));

        assertEquals("30006", failure.getCode());
        verify(guidanceService, never()).acknowledge(anyLong(), anyLong(), anyLong(),
                any(), any(), any());
    }

    @Test
    void workitemGuidanceAckWithoutDispatchIdDoesNotCallScheduledGuard() {
        when(guidanceService.bindingForInboundAcknowledgement(100L, 1L, 77L))
                .thenReturn(new GuidanceService.InboundAcknowledgementBinding(42L,
                        new ArtifactOwnerRef(ExecutionSourceType.WORKITEM, 200L)));

        router.route(session(1L, 10L, 100L),
                "{\"type\":\"TASK_GUIDANCE_ACK\",\"guidanceId\":77,\"status\":\"APPLIED\"}");

        verify(guidanceService).acknowledge(100L, 1L, 77L, "APPLIED", null, null);
        verifyNoInteractions(capabilityGuard);
    }

    @Test
    void scheduledGuidanceAckIgnoresUnrelatedWorkitemDispatchIdBeforeAnySideEffect() {
        InteractionWorkflowService workflowService = mock(InteractionWorkflowService.class);
        InboundFrameRouter workflowRouter = new InboundFrameRouter(dispatchService, artifactService,
                presenceManager, handoffService, drainScheduler, pauseService, guidanceService,
                workflowService, null, null);
        ReflectionTestUtils.setField(workflowRouter, "capabilityGuard", capabilityGuard);
        when(guidanceService.bindingForInboundAcknowledgement(100L, 1L, 77L))
                .thenReturn(new GuidanceService.InboundAcknowledgementBinding(88L,
                        new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK_RUN, 200L)));
        when(dispatchService.artifactOwnerForInbound(100L, 42L))
                .thenReturn(new ArtifactOwnerRef(ExecutionSourceType.WORKITEM, 42L));
        doThrow(new BizException(ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY))
                .when(capabilityGuard).requireAvailable("daemon");

        BizException failure = assertThrows(BizException.class, () -> workflowRouter.route(
                session(1L, 10L, 100L),
                "{\"type\":\"TASK_GUIDANCE_ACK\",\"guidanceId\":77,\"dispatchId\":42," +
                        "\"status\":\"APPLIED\",\"workflowPlan\":{\"targetAgentId\":40044}}"));

        assertEquals("30006", failure.getCode());
        verify(dispatchService, never()).artifactOwnerForInbound(100L, 42L);
        verify(guidanceService, never()).acknowledge(anyLong(), anyLong(), anyLong(),
                any(), any(), any());
        verifyNoInteractions(workflowService);
    }

    @Test
    void availableScheduledGuidanceAckRejectsMismatchedFrameDispatchIdBeforeWorkflow() {
        InteractionWorkflowService workflowService = mock(InteractionWorkflowService.class);
        InboundFrameRouter workflowRouter = new InboundFrameRouter(dispatchService, artifactService,
                presenceManager, handoffService, drainScheduler, pauseService, guidanceService,
                workflowService, null, null);
        ReflectionTestUtils.setField(workflowRouter, "capabilityGuard", capabilityGuard);
        when(guidanceService.bindingForInboundAcknowledgement(100L, 1L, 77L))
                .thenReturn(new GuidanceService.InboundAcknowledgementBinding(88L,
                        new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK_RUN, 200L)));

        BizException failure = assertThrows(BizException.class, () -> workflowRouter.route(
                session(1L, 10L, 100L),
                "{\"type\":\"TASK_GUIDANCE_ACK\",\"guidanceId\":77,\"dispatchId\":42," +
                        "\"status\":\"APPLIED\",\"workflowPlan\":{\"targetAgentId\":40044}}"));

        assertEquals(ErrorCode.NO_PERMISSION.getCode(), failure.getCode());
        verifyNoInteractions(workflowService);
        verify(guidanceService, never()).acknowledge(anyLong(), anyLong(), anyLong(),
                any(), any(), any());
    }

    @Test
    void unresolvedGuidanceAckFailsClosedBeforeAnyClientDispatchSideEffect() {
        InteractionWorkflowService workflowService = mock(InteractionWorkflowService.class);
        InboundFrameRouter workflowRouter = new InboundFrameRouter(dispatchService, artifactService,
                presenceManager, handoffService, drainScheduler, pauseService, guidanceService,
                workflowService, null, null);
        ReflectionTestUtils.setField(workflowRouter, "capabilityGuard", capabilityGuard);
        when(guidanceService.bindingForInboundAcknowledgement(100L, 1L, 77L)).thenReturn(null);

        BizException failure = assertThrows(BizException.class, () -> workflowRouter.route(
                session(1L, 10L, 100L),
                "{\"type\":\"TASK_GUIDANCE_ACK\",\"guidanceId\":77,\"dispatchId\":42," +
                        "\"status\":\"APPLIED\",\"workflowPlan\":{\"targetAgentId\":40044}}"));

        assertEquals(ErrorCode.NO_PERMISSION.getCode(), failure.getCode());
        verify(dispatchService, never()).artifactOwnerForInbound(anyLong(), anyLong());
        verify(guidanceService, never()).acknowledge(anyLong(), anyLong(), anyLong(),
                any(), any(), any());
        verifyNoInteractions(workflowService, capabilityGuard);
    }

    @Test
    void workflowGuidanceAckPublishesSuccessOnlyAfterFormalDispatchIsCreated() {
        InteractionWorkflowService workflowService = mock(InteractionWorkflowService.class);
        InboundFrameRouter workflowRouter = new InboundFrameRouter(dispatchService, artifactService,
                presenceManager, handoffService, drainScheduler, pauseService, guidanceService,
                workflowService, null, null);
        DispatchDO formal = new DispatchDO();
        formal.setId(88L);
        when(workflowService.applyFromExecutor(eq(100L), eq(1L), eq(42L), any())).thenReturn(formal);

        workflowRouter.route(session(1L, 10L, 100L),
                "{\"type\":\"TASK_GUIDANCE_ACK\",\"guidanceId\":77,\"dispatchId\":42," +
                        "\"status\":\"APPLIED\",\"replyMarkdown\":\"收到，已转入正式工作流程。\"," +
                        "\"workflowPlan\":{\"targetAgentId\":40044,\"targetStepHint\":\"冲突上下文获取与复现\"}}");

        InOrder order = inOrder(workflowService, guidanceService);
        order.verify(workflowService).applyFromExecutor(eq(100L), eq(1L), eq(42L), any());
        order.verify(guidanceService).acknowledge(100L, 1L, 77L, "APPLIED", null,
                "收到，已转入正式工作流程。");
    }

    @Test
    void workflowGuidanceAckExposesRoutingFailureInsteadOfPublishingFalseSuccess() {
        InteractionWorkflowService workflowService = mock(InteractionWorkflowService.class);
        InboundFrameRouter workflowRouter = new InboundFrameRouter(dispatchService, artifactService,
                presenceManager, handoffService, drainScheduler, pauseService, guidanceService,
                workflowService, null, null);
        when(workflowService.applyFromExecutor(eq(100L), eq(1L), eq(42L), any())).thenReturn(null);

        workflowRouter.route(session(1L, 10L, 100L),
                "{\"type\":\"TASK_GUIDANCE_ACK\",\"guidanceId\":77,\"dispatchId\":42," +
                        "\"status\":\"APPLIED\",\"replyMarkdown\":\"收到，已转入正式工作流程。\"," +
                        "\"workflowPlan\":{\"targetAgentId\":40044,\"targetStepHint\":\"interaction\"}}");

        verify(guidanceService).acknowledge(eq(100L), eq(1L), eq(77L), eq("FAILED"),
                contains("正式工作流程创建失败"), isNull());
        verify(guidanceService, never()).acknowledge(anyLong(), anyLong(), anyLong(), eq("APPLIED"),
                any(), any());
    }

    @Test
    void taskProgressCallsOnProgress() {
        router.route(session(1L, 10L, 100L), "{\"type\":\"TASK_PROGRESS\",\"dispatchId\":55}");
        verify(dispatchService).onProgress(eq(100L), eq(55L), any());
        verify(guidanceService).deliverQueuedForDispatch(100L, 55L);
    }

    @Test
    void taskProgressPassesRuntimeEventFrame() {
        router.route(session(1L, 10L, 100L),
                "{\"type\":\"TASK_PROGRESS\",\"dispatchId\":55,\"resultSummary\":\"step.started\",\"stepOrder\":3}");
        verify(dispatchService).onProgress(eq(100L), eq(55L), argThat(json ->
                "step.started".equals(json.getString("resultSummary")) && json.getInteger("stepOrder") == 3));
    }

    @Test
    void taskResultSuccessCallsOnResult() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.hasDurableCheckpoint(100L, 55L, 7L, "sha256:abc")).thenReturn(true);
        when(dispatchService.onResult(100L, 1L, 55L, true, "done", null, false, false, null)).thenReturn(true);
        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true,\"resultSummary\":\"done\"," +
                        "\"checkpointReceiptVersion\":1,\"checkpointSeq\":7,\"checkpointSha256\":\"sha256:abc\"}");
        verify(dispatchService).onResult(100L, 1L, 55L, true, "done", null, false, false, null);
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")
                && text.contains("55")));
    }

    @Test
    void taskResultPassesWorkflowChangedFlag() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.onResult(100L, 1L, 55L, true, "done", null, true, false, null)).thenReturn(true);
        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true," +
                        "\"resultSummary\":\"done\",\"workflowChanged\":true}");
        verify(dispatchService).onResult(100L, 1L, 55L, true, "done", null, true, false, null);
    }

    @Test
    void taskResultFailureCallsOnResult() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.onResult(100L, 1L, 55L, false, null, "oops", false, false, null)).thenReturn(true);
        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":false,\"error\":\"oops\"}");
        verify(dispatchService).onResult(100L, 1L, 55L, false, null, "oops", false, false, null);
        verify(guidanceService).failForDispatch(100L, 55L, "oops");
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")));
    }

    @Test
    void executorScopedProviderFailureUsesFailoverWithoutFailingGuidance() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.onExecutorUnavailableResult(100L, 1L, 55L,
                "agent_error.provider_quota_limit", "quota exhausted")).thenReturn(true);

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":false," +
                        "\"error\":\"quota exhausted\",\"failureScope\":\"EXECUTOR\"," +
                        "\"failureCategory\":\"agent_error.provider_quota_limit\"}");

        InOrder recovery = inOrder(dispatchService, guidanceService);
        recovery.verify(dispatchService).onExecutorUnavailableResult(100L, 1L, 55L,
                "agent_error.provider_quota_limit", "quota exhausted");
        recovery.verify(guidanceService).requeueForExecutorFailover(100L, 55L);
        recovery.verify(dispatchService).runPending(55L);
        verify(dispatchService, never()).onResult(anyLong(), anyLong(), anyLong(), anyBoolean(),
                any(), any(), anyBoolean(), anyBoolean(), any());
        verify(guidanceService, never()).failForDispatch(anyLong(), anyLong(), any());
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")));
    }

    @Test
    void legacyUsageLimitFailureUsesExecutorFailover() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        String error = "You've hit your usage limit. Purchase more credits or try again later.";
        when(dispatchService.onExecutorUnavailableResult(100L, 1L, 55L,
                "agent_error.provider_quota_limit", error)).thenReturn(true);

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":false," +
                        "\"error\":\"You've hit your usage limit. Purchase more credits or try again later.\"}");

        verify(dispatchService).onExecutorUnavailableResult(100L, 1L, 55L,
                "agent_error.provider_quota_limit", error);
        verify(dispatchService, never()).onResult(anyLong(), anyLong(), anyLong(), anyBoolean(),
                any(), any(), anyBoolean(), anyBoolean(), any());
        verify(guidanceService).requeueForExecutorFailover(100L, 55L);
    }

    @Test
    void legacyOrdinaryTaskFailureDoesNotUseExecutorFailover() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.onResult(100L, 1L, 55L, false, null,
                "tests failed", false, false, null)).thenReturn(true);

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":false," +
                        "\"error\":\"tests failed\"}");

        verify(dispatchService, never()).onExecutorUnavailableResult(anyLong(), anyLong(), anyLong(), any(), any());
        verify(dispatchService).onResult(100L, 1L, 55L, false, null, "tests failed", false, false, null);
    }

    @Test
    void runtimeDeclaredToolHookBlockageIsPassedThroughWithoutExecutorFailover() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        String error = "tool_hook_blocked: kind safety_denial hook jarvis-tool-safety "
                + "trigger beforeTool status blocked exitCode 2 blockedCalls 3";
        when(dispatchService.onResult(100L, 1L, 55L, false, null, error, false, false,
                "tool_hook_blocked")).thenReturn(true);

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":false," +
                        "\"failureCategory\":\"tool_hook_blocked\",\"error\":\"" + error + "\"}");

        verify(dispatchService).onResult(100L, 1L, 55L, false, null, error, false, false,
                "tool_hook_blocked");
        verify(dispatchService, never()).onExecutorUnavailableResult(anyLong(), anyLong(), anyLong(), any(), any());
        verify(guidanceService, never()).requeueForExecutorFailover(anyLong(), anyLong());
        verify(dispatchService, never()).runPending(anyLong());
        verify(guidanceService).failForDispatch(100L, 55L, error);
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")
                && text.contains("\"accepted\":true")));
    }

    @Test
    void runtimeDeclaredToolHookBlockageIsNotRelabelledByUsageLimitHeuristic() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        String error = "You've hit your usage limit. Purchase more credits or try again later.";
        when(dispatchService.onResult(100L, 1L, 55L, false, null, error, false, false,
                "tool_hook_blocked")).thenReturn(true);

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":false," +
                        "\"failureCategory\":\"tool_hook_blocked\",\"error\":\"" + error + "\"}");

        verify(dispatchService).onResult(100L, 1L, 55L, false, null, error, false, false,
                "tool_hook_blocked");
        verify(dispatchService, never()).onExecutorUnavailableResult(anyLong(), anyLong(), anyLong(), any(), any());
        verify(guidanceService, never()).requeueForExecutorFailover(anyLong(), anyLong());
        verify(dispatchService, never()).runPending(anyLong());
        verify(guidanceService).failForDispatch(100L, 55L, error);
    }

    @Test
    void terminallyRejectedTaskResultIsAcknowledgedWithoutRoutingHandoff() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.onResult(100L, 1L, 55L, false, null, "stale", false, false, null)).thenReturn(false);

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"workitemId\":200," +
                        "\"success\":false,\"error\":\"stale\"," +
                        "\"handoff\":{\"to\":\"AW_CR\",\"toType\":\"AGENT\"}}");

        verifyNoInteractions(handoffService);
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")
                && text.contains("\"accepted\":false")));
    }

    @Test
    void successfulTaskResultWithoutDurableCheckpointIsRejectedAndAcknowledged() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true,\"resultSummary\":\"done\"," +
                        "\"checkpointReceiptVersion\":1}");

        verify(dispatchService).hasDurableCheckpoint(100L, 55L, 0L, null);
        verify(dispatchService, never()).onResult(anyLong(), anyLong(), anyLong(), anyBoolean(), any(), any(), anyBoolean(), anyBoolean(), any());
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")
                && text.contains("\"accepted\":false")));
    }

    @Test
    void legacySuccessfulTaskResultRemainsAcceptedDuringProtocolRollout() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.onResult(100L, 1L, 55L, true, "done", null, false, false, null)).thenReturn(true);

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true,\"resultSummary\":\"done\"}");

        verify(dispatchService).onResult(100L, 1L, 55L, true, "done", null, false, false, null);
        verify(dispatchService, never()).hasDurableCheckpoint(anyLong(), anyLong(), anyLong(), any());
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")));
    }

    @Test
    void taskBusyReturnsOwnedDispatchToPending() {
        router.route(session(1L, 10L, 100L),
                "{\"type\":\"TASK_BUSY\",\"dispatchId\":55,\"reason\":\"AT_CAPACITY\"}");

        verify(dispatchService).onBusy(100L, 1L, 55L);
    }

    @Test
    void artifactUploadedCallsRecord() {
        when(dispatchService.artifactOwnerForInbound(100L, 55L))
                .thenReturn(new ArtifactOwnerRef(ExecutionSourceType.WORKITEM, 200L));
        String json = "{\"type\":\"ARTIFACT_UPLOADED\",\"dispatchId\":55,\"workitemId\":200," +
                "\"name\":\"patch.diff\",\"artifactType\":\"PATCH\",\"ossRef\":\"oss://x\",\"size\":1024}";
        router.route(session(1L, 10L, 100L), json);

        ArgumentCaptor<ReportArtifactRequest> cap = ArgumentCaptor.forClass(ReportArtifactRequest.class);
        verify(artifactService).record(cap.capture(), eq(100L),
                eq(new ArtifactOwnerRef(ExecutionSourceType.WORKITEM, 200L)));
        ReportArtifactRequest req = cap.getValue();
        assertEquals(55L, req.getDispatchId());
        assertEquals(200L, req.getWorkitemId());
        assertEquals("patch.diff", req.getName());
        assertEquals("PATCH", req.getType());
        assertEquals("oss://x", req.getOssRef());
        assertEquals(1024L, req.getSize());
        verifyNoInteractions(capabilityGuard);
    }

    @Test
    void scheduledArtifactFrameFailsBeforeScheduledArtifactServiceWhenUnavailable() {
        when(dispatchService.artifactOwnerForInbound(100L, 55L))
                .thenReturn(new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK_RUN, 200L));
        doThrow(new BizException(ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY))
                .when(capabilityGuard).requireAvailable("daemon");

        assertThrows(BizException.class, () -> router.route(session(1L, 10L, 100L),
                "{\"type\":\"ARTIFACT_UPLOADED\",\"dispatchId\":55,\"workitemId\":200}"));

        verifyNoInteractions(artifactService);
    }

    @Test
    void scheduledProgressAndResultFailBeforeDispatchSideEffectsWhenUnavailable() {
        when(dispatchService.artifactOwnerForInbound(100L, 55L))
                .thenReturn(new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK_RUN, 200L));
        doThrow(new BizException(ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY))
                .when(capabilityGuard).requireAvailable("daemon");

        assertThrows(BizException.class, () -> router.route(session(1L, 10L, 100L),
                "{\"type\":\"TASK_PROGRESS\",\"dispatchId\":55}"));
        assertThrows(BizException.class, () -> router.route(session(1L, 10L, 100L),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":false}"));

        verify(dispatchService, never()).onProgress(anyLong(), anyLong(), any());
        verify(dispatchService, never()).onResult(anyLong(), anyLong(), anyLong(), anyBoolean(),
                any(), any(), anyBoolean(), anyBoolean(), any());
        verifyNoInteractions(guidanceService, pauseService);
    }

    @Test
    void workitemProgressResolvesDurableSourceWithoutScheduledGuard() {
        when(dispatchService.artifactOwnerForInbound(100L, 55L))
                .thenReturn(new ArtifactOwnerRef(ExecutionSourceType.WORKITEM, 200L));

        router.route(session(1L, 10L, 100L),
                "{\"type\":\"TASK_PROGRESS\",\"dispatchId\":55}");

        verify(dispatchService).onProgress(eq(100L), eq(55L), any());
        verifyNoInteractions(capabilityGuard);
    }

    @Test
    void taskHandoffCallsHandle() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(handoffService.handle(100L, 200L, 55L, "Alice", "HUMAN"))
                .thenReturn(HandoffResult.human(42L, "REQUESTED_HUMAN"));

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_HANDOFF\",\"dispatchId\":55,\"workitemId\":200,\"to\":\"Alice\",\"toType\":\"HUMAN\"}");

        verify(handoffService).handle(100L, 200L, 55L, "Alice", "HUMAN");
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_HANDOFF_RESULT")
                && text.contains("HUMAN_ASSIGNED") && text.contains("REQUESTED_HUMAN")));
    }

    @Test
    void taskResultRoutesEmbeddedHandoffAndReplies() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(handoffService.handle(100L, 200L, 55L, "AW_CR", "AGENT"))
                .thenReturn(HandoffResult.agent(12L, 56L));
        when(dispatchService.hasDurableCheckpoint(100L, 55L, 7L, "sha256:abc")).thenReturn(true);
        when(dispatchService.onResult(100L, 1L, 55L, true, null, null, false, true, null)).thenReturn(true);
        when(dispatchService.mayRouteHandoff(100L, 1L, 55L)).thenReturn(true);

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"workitemId\":200," +
                        "\"success\":true,\"checkpointReceiptVersion\":1,\"checkpointSeq\":7,\"checkpointSha256\":\"sha256:abc\"," +
                        "\"handoff\":{\"to\":\"AW_CR\",\"toType\":\"AGENT\"}}");

        verify(dispatchService).onResult(100L, 1L, 55L, true, null, null, false, true, null);
        verify(handoffService).handle(100L, 200L, 55L, "AW_CR", "AGENT");
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_HANDOFF_RESULT")
                && text.contains("AGENT_DISPATCHED") && text.contains("56")));
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")));
    }

    @Test
    void successfulResultRacingWithPauseActivatesReworkWithoutDrivingOldFlow() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        InteractionWorkflowService workflowService = mock(InteractionWorkflowService.class);
        InboundFrameRouter interactionRouter = new InboundFrameRouter(dispatchService, artifactService,
                presenceManager, handoffService, drainScheduler, pauseService, guidanceService,
                workflowService, null, null);
        when(dispatchService.hasDurableCheckpoint(100L, 55L, 7L, "sha256:abc")).thenReturn(true);
        when(pauseService.onCompletedWhilePausing(100L, 1L, 55L, 7L, "sha256:abc"))
                .thenReturn(DispatchPauseService.CompletionDisposition.PAUSED);

        interactionRouter.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true," +
                        "\"checkpointReceiptVersion\":1,\"checkpointSeq\":7," +
                        "\"checkpointSha256\":\"sha256:abc\"}");

        verify(dispatchService, never()).onResult(anyLong(), anyLong(), anyLong(), anyBoolean(),
                any(), any(), anyBoolean(), anyBoolean(), any());
        verify(workflowService).onPaused(100L, 55L);
        verify(guidanceService).requeueDeliveredForDispatch(100L, 55L);
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")
                && text.contains("\"accepted\":true")));
    }

    @Test
    void sideInteractionResultDelegatesWorkflowPlanToServerAuthority() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        InteractionWorkflowService workflowService = mock(InteractionWorkflowService.class);
        InboundFrameRouter interactionRouter = new InboundFrameRouter(dispatchService, artifactService,
                presenceManager, handoffService, drainScheduler, pauseService, guidanceService, workflowService, null, null);
        when(dispatchService.hasDurableCheckpoint(100L, 55L, 7L, "sha256:abc")).thenReturn(true);
        when(dispatchService.onResult(100L, 1L, 55L, true, "reply", null, false, false, null)).thenReturn(true);

        interactionRouter.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"workitemId\":200," +
                        "\"success\":true,\"resultSummary\":\"reply\",\"checkpointReceiptVersion\":1," +
                        "\"checkpointSeq\":7,\"checkpointSha256\":\"sha256:abc\"," +
                        "\"workflowPlan\":{\"targetAgentId\":40013,\"targetStepHint\":\"编码实现\"}} ");

        verify(workflowService).apply(eq(100L), eq(55L), argThat(plan ->
                plan.getLongValue("targetAgentId") == 40013L && "编码实现".equals(plan.getString("targetStepHint"))));
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")));
    }

    @Test
    void acceptedLateResultDoesNotRouteHandoffWhenDispatchIsNoLongerCurrent() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.hasDurableCheckpoint(100L, 55L, 7L, "sha256:abc")).thenReturn(true);
        when(dispatchService.onResult(100L, 1L, 55L, true, null, null, false, false, null)).thenReturn(true);
        when(dispatchService.mayRouteHandoff(100L, 1L, 55L)).thenReturn(false);

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"workitemId\":200," +
                        "\"success\":true,\"checkpointReceiptVersion\":1,\"checkpointSeq\":7,\"checkpointSha256\":\"sha256:abc\"," +
                        "\"handoff\":{\"to\":\"AW_CR\",\"toType\":\"AGENT\"}}");

        verifyNoInteractions(handoffService);
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")));
    }

    @Test
    void failedTaskResultDoesNotRouteEmbeddedHandoff() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.onResult(100L, 1L, 55L, false, null, null, false, false, null)).thenReturn(true);
        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"workitemId\":200," +
                        "\"success\":false,\"handoff\":{\"to\":\"AW_CR\",\"toType\":\"AGENT\"}}");

        verify(dispatchService).onResult(100L, 1L, 55L, false, null, null, false, false, null);
        verifyNoInteractions(handoffService);
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")));
    }

    @Test
    void unknownTypeIsIgnored() {
        router.route(session(1L, 10L, 100L), "{\"type\":\"UNKNOWN\"}");
        verifyNoInteractions(dispatchService, artifactService, presenceManager, handoffService);
    }

    @Test
    void malformedJsonIsIgnored() {
        router.route(session(1L, 10L, 100L), "not json at all");
        verifyNoInteractions(dispatchService, artifactService, presenceManager, handoffService);
    }

    @Test
    void inventoryHeartbeatPersistsHeartbeatAndPassesKnownEmptyLease() {
        ExecutorService executorService = mock(ExecutorService.class);
        InboundFrameRouter routerWithExecSvc = new InboundFrameRouter(dispatchService, artifactService,
                presenceManager, handoffService, drainScheduler, pauseService, guidanceService,
                null, null, null, null, executorService);
        routerWithExecSvc.route(session(1L, 10L, 100L), heartbeat());

        verify(executorService).persistHeartbeatIfNeeded(1L, 100L);
        verify(dispatchService).renewActiveLeases(eq(100L), eq(1L), eq(java.util.List.of()));
    }

    @Test
    void heartbeatRejected_skipsPersistHeartbeat() throws Exception {
        ExecutorService executorService = mock(ExecutorService.class);
        InboundFrameRouter routerWithExecSvc = new InboundFrameRouter(dispatchService, artifactService,
                presenceManager, handoffService, drainScheduler, pauseService, guidanceService,
                null, null, null, null, executorService);
        Session ws = mock(Session.class);
        ExecutorSession es = new ExecutorSession(1L, 10L, 100L, ws);
        when(presenceManager.publishHeartbeat(eq(1L), eq(10L), any(), any(), anyCollection(),
                any(), any())).thenReturn(PresenceManager.SessionMutationResult.DELETED);

        routerWithExecSvc.route(es, heartbeat());

        verify(executorService, never()).persistHeartbeatIfNeeded(anyLong(), anyLong());
        verify(ws).close();
    }

    @Test
    void heartbeatAccepted_nullExecutorService_doesNotFail() {
        assertDoesNotThrow(() ->
                router.route(session(1L, 10L, 100L), heartbeat()));
        verify(dispatchService).renewActiveLeases(eq(100L), eq(1L), eq(java.util.List.of()));
    }

    @Test
    void acceptedHeartbeatRequestsProviderCatalogRefreshAfterNormalHeartbeatWork() {
        ProviderModelCatalogService catalogService = mock(ProviderModelCatalogService.class);
        router.setProviderModelCatalogService(catalogService);
        router.route(session(1L, 10L, 100L), heartbeat());

        InOrder order = inOrder(dispatchService, drainScheduler, catalogService);
        order.verify(dispatchService).renewActiveLeases(100L, 1L, java.util.List.of());
        order.verify(drainScheduler).request(10L);
        order.verify(catalogService).requestRefreshForExecutor(1L);
    }

    @Test
    void rejectedHeartbeatDoesNotRequestProviderCatalogRefresh() {
        ProviderModelCatalogService catalogService = mock(ProviderModelCatalogService.class);
        router.setProviderModelCatalogService(catalogService);
        when(presenceManager.publishHeartbeat(eq(1L), eq(10L), any(), any(), anyCollection(),
                any(), any())).thenReturn(PresenceManager.SessionMutationResult.DELETED);

        router.route(session(1L, 10L, 100L), heartbeat());

        verifyNoInteractions(catalogService);
    }

    @Test
    void heartbeatWithActiveDispatchesDoesNotRequestProviderCatalogRefresh() {
        ProviderModelCatalogService catalogService = mock(ProviderModelCatalogService.class);
        router.setProviderModelCatalogService(catalogService);
        router.route(session(1L, 10L, 100L),
                heartbeat("runningDispatchIds", java.util.List.of(55L)));

        verifyNoInteractions(catalogService);
    }

    @Test
    void catalogResultFrameDelegatesToProviderCatalogService() {
        ProviderModelCatalogService catalogService = mock(ProviderModelCatalogService.class);
        router.setProviderModelCatalogService(catalogService);

        router.route(session(1L, 10L, 100L),
                "{\"type\":\"QODER_MODEL_CATALOG_RESULT\",\"requestId\":\"request-1\",\"provider\":\"qoder\","
                        + "\"success\":true,\"models\":[{\"id\":\"qmodel\",\"name\":\"Qwen\"}]}");

        verify(catalogService).complete(eq(100L), eq(1L), argThat(frame ->
                "request-1".equals(frame.getString("requestId"))
                        && "qoder".equals(frame.getString("provider"))));
    }

    @Test
    void taskResultDebugLogSegmentIsRecordedAfterResultProcessing() throws Exception {
        com.aliyun.autowonder.debuglog.DebugLogService debugLogService =
                mock(com.aliyun.autowonder.debuglog.DebugLogService.class);
        router.setDebugLogService(debugLogService);
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.artifactOwnerForInbound(100L, 55L))
                .thenReturn(new com.aliyun.autowonder.artifact.ArtifactOwnerRef(
                        ExecutionSourceType.WORKITEM, 200L));
        when(dispatchService.onResult(100L, 1L, 55L, true, "done", null, false, false, null)).thenReturn(true);

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true,\"resultSummary\":\"done\","
                        + "\"debugLog\":{\"status\":\"UPLOADED\",\"channel\":\"DIRECT\",\"sizeBytes\":123,"
                        + "\"sha256\":\"abc\",\"truncated\":false}}");

        ArgumentCaptor<JSONObject> cap = ArgumentCaptor.forClass(JSONObject.class);
        verify(debugLogService).recordTaskResultReport(eq(100L), eq(1L), eq(55L), cap.capture());
        org.junit.jupiter.api.Assertions.assertEquals("UPLOADED", cap.getValue().getString("status"));
        org.junit.jupiter.api.Assertions.assertEquals("DIRECT", cap.getValue().getString("channel"));
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")
                && text.contains("\"accepted\":true")));
    }

    @Test
    void debugLogRecordingFailureNeverBlocksResultAck() throws Exception {
        com.aliyun.autowonder.debuglog.DebugLogService debugLogService =
                mock(com.aliyun.autowonder.debuglog.DebugLogService.class);
        router.setDebugLogService(debugLogService);
        doThrow(new RuntimeException("db down")).when(debugLogService)
                .recordTaskResultReport(anyLong(), anyLong(), anyLong(), any());
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.artifactOwnerForInbound(100L, 55L))
                .thenReturn(new com.aliyun.autowonder.artifact.ArtifactOwnerRef(
                        ExecutionSourceType.WORKITEM, 200L));
        when(dispatchService.onResult(100L, 1L, 55L, true, "done", null, false, false, null)).thenReturn(true);

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true,\"resultSummary\":\"done\","
                        + "\"debugLog\":{\"status\":\"UPLOADED\"}}");

        verify(basicRemote).sendText(argThat(text -> text.contains("\"accepted\":true")));
    }

    @Test
    void debugLogRecordingFailureWarnsWithReasonToken() throws Exception {
        com.aliyun.autowonder.debuglog.DebugLogService debugLogService =
                mock(com.aliyun.autowonder.debuglog.DebugLogService.class);
        router.setDebugLogService(debugLogService);
        doThrow(new RuntimeException("db down")).when(debugLogService)
                .recordTaskResultReport(anyLong(), anyLong(), anyLong(), any());
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.artifactOwnerForInbound(100L, 55L))
                .thenReturn(new com.aliyun.autowonder.artifact.ArtifactOwnerRef(
                        ExecutionSourceType.WORKITEM, 200L));
        when(dispatchService.onResult(100L, 1L, 55L, true, "done", null, false, false, null)).thenReturn(true);

        // Issue 2：catch-all warn 补 reason token，与服务侧措辞一致、供 grep 统一。
        List<String> warns = captureRouterWarns(() -> router.route(
                sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true,\"resultSummary\":\"done\","
                        + "\"debugLog\":{\"status\":\"UPLOADED\"}}"));

        assertTrue(warns.stream().anyMatch(m -> m.contains("debug log report ignored")
                        && m.contains("dispatchId=55")
                        && m.contains("reason=DEBUG_LOG_REPORT_IGNORED")),
                "catch-all warn 必须带 reason token：" + warns);
        verify(basicRemote).sendText(argThat(text -> text.contains("\"accepted\":true")));
    }

    @Test
    void taskResultWithoutDebugLogSegmentDoesNotTouchDebugService() {
        com.aliyun.autowonder.debuglog.DebugLogService debugLogService =
                mock(com.aliyun.autowonder.debuglog.DebugLogService.class);
        router.setDebugLogService(debugLogService);
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        // owner 必须非空：否则 guard 在 owner==null 处短路，测不到「段缺失」这条路径
        when(dispatchService.artifactOwnerForInbound(100L, 55L))
                .thenReturn(new com.aliyun.autowonder.artifact.ArtifactOwnerRef(
                        ExecutionSourceType.WORKITEM, 200L));
        when(dispatchService.onResult(100L, 1L, 55L, true, "done", null, false, false, null)).thenReturn(true);

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true,\"resultSummary\":\"done\"}");

        verifyNoInteractions(debugLogService);
    }

    /** 捕获 InboundFrameRouter 在 action 执行期间产生的 WARN 级格式化消息。 */
    private static List<String> captureRouterWarns(Runnable action) {
        Logger logger = (Logger) LogManager.getLogger(InboundFrameRouter.class);
        Level previousLevel = logger.getLevel();
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            action.run();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
        return appender.events.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(event -> event.getMessage().getFormattedMessage())
                .toList();
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new java.util.ArrayList<>();

        private CapturingAppender() {
            super("inbound-frame-router-test", null, PatternLayout.createDefaultLayout(), true,
                    Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
