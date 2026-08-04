package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.artifact.ArtifactService;
import com.aliyun.autowonder.artifact.dto.ReportArtifactRequest;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.HandoffResult;
import com.aliyun.autowonder.dispatch.HandoffService;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.executor.ExecutorService;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.guidance.InteractionWorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import javax.websocket.Session;
import javax.websocket.RemoteEndpoint;

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

    @BeforeEach
    void setUp() {
        dispatchService = mock(DispatchService.class);
        artifactService = mock(ArtifactService.class);
        presenceManager = mock(PresenceManager.class);
        handoffService = mock(HandoffService.class);
        drainScheduler = mock(DispatchDrainScheduler.class);
        pauseService = mock(DispatchPauseService.class);
        guidanceService = mock(GuidanceService.class);
        router = new InboundFrameRouter(dispatchService, artifactService, presenceManager,
                handoffService, drainScheduler, pauseService, guidanceService);
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

    @Test
    void taskResultReplyUsesTheSameSerializedBasicWriterAsDispatchFrames() throws Exception {
        RemoteEndpoint.Async asyncRemote = mock(RemoteEndpoint.Async.class);
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        Session ws = mock(Session.class);
        when(ws.getAsyncRemote()).thenReturn(asyncRemote);
        when(ws.getBasicRemote()).thenReturn(basicRemote);
        when(ws.isOpen()).thenReturn(true);
        when(dispatchService.hasDurableCheckpoint(100L, 55L, 7L, "sha256:abc")).thenReturn(true);
        when(dispatchService.onResult(100L, 1L, 55L, true, "done", null, false)).thenReturn(true);

        router.route(new ExecutorSession(1L, 10L, 100L, ws),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true,\"resultSummary\":\"done\"," +
                        "\"checkpointReceiptVersion\":1,\"checkpointSeq\":7,\"checkpointSha256\":\"sha256:abc\"}");

        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")
                && text.contains("55")));
        verify(asyncRemote, never()).sendText(anyString());
    }

    @Test
    void heartbeatRenewsPresence() {
        when(presenceManager.heartbeat(1L, 10L, 1)).thenReturn(true);
        router.route(session(1L, 10L, 100L),
                "{\"type\":\"HEARTBEAT\",\"runningDispatchIds\":[55,56]}");
        verify(presenceManager).heartbeat(1L, 10L, 1);
        verify(dispatchService).renewActiveLeases(100L, 1L, java.util.List.of(55L, 56L));
        verify(drainScheduler).request(10L);
        verify(dispatchService, never()).drainPending(anyLong());
    }

    @Test
    void heartbeatRejectedByTombstoneClosesSessionAndSkipsDownstream() throws Exception {
        Session ws = mock(Session.class);
        ExecutorSession es = new ExecutorSession(1L, 10L, 100L, ws);
        when(presenceManager.heartbeat(1L, 10L, 1)).thenReturn(false);

        router.route(es, "{\"type\":\"HEARTBEAT\"}");

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
        when(dispatchService.onResult(100L, 1L, 55L, true, "done", null, false)).thenReturn(true);
        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true,\"resultSummary\":\"done\"," +
                        "\"checkpointReceiptVersion\":1,\"checkpointSeq\":7,\"checkpointSha256\":\"sha256:abc\"}");
        verify(dispatchService).onResult(100L, 1L, 55L, true, "done", null, false);
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")
                && text.contains("55")));
    }

    @Test
    void taskResultPassesWorkflowChangedFlag() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.onResult(100L, 1L, 55L, true, "done", null, true)).thenReturn(true);
        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true," +
                        "\"resultSummary\":\"done\",\"workflowChanged\":true}");
        verify(dispatchService).onResult(100L, 1L, 55L, true, "done", null, true);
    }

    @Test
    void taskResultFailureCallsOnResult() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.onResult(100L, 1L, 55L, false, null, "oops", false)).thenReturn(true);
        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":false,\"error\":\"oops\"}");
        verify(dispatchService).onResult(100L, 1L, 55L, false, null, "oops", false);
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
                any(), any(), anyBoolean());
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
                any(), any(), anyBoolean());
        verify(guidanceService).requeueForExecutorFailover(100L, 55L);
    }

    @Test
    void legacyOrdinaryTaskFailureDoesNotUseExecutorFailover() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.onResult(100L, 1L, 55L, false, null,
                "tests failed", false)).thenReturn(true);

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":false," +
                        "\"error\":\"tests failed\"}");

        verify(dispatchService, never()).onExecutorUnavailableResult(anyLong(), anyLong(), anyLong(), any(), any());
        verify(dispatchService).onResult(100L, 1L, 55L, false, null, "tests failed", false);
    }

    @Test
    void terminallyRejectedTaskResultIsAcknowledgedWithoutRoutingHandoff() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.onResult(100L, 1L, 55L, false, null, "stale", false)).thenReturn(false);

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
        verify(dispatchService, never()).onResult(anyLong(), anyLong(), anyLong(), anyBoolean(), any(), any(), anyBoolean());
        verify(basicRemote).sendText(argThat(text -> text.contains("TASK_RESULT_ACK")
                && text.contains("\"accepted\":false")));
    }

    @Test
    void legacySuccessfulTaskResultRemainsAcceptedDuringProtocolRollout() throws Exception {
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(dispatchService.onResult(100L, 1L, 55L, true, "done", null, false)).thenReturn(true);

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true,\"resultSummary\":\"done\"}");

        verify(dispatchService).onResult(100L, 1L, 55L, true, "done", null, false);
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
        String json = "{\"type\":\"ARTIFACT_UPLOADED\",\"dispatchId\":55,\"workitemId\":200," +
                "\"name\":\"patch.diff\",\"artifactType\":\"PATCH\",\"ossRef\":\"oss://x\",\"size\":1024}";
        router.route(session(1L, 10L, 100L), json);

        ArgumentCaptor<ReportArtifactRequest> cap = ArgumentCaptor.forClass(ReportArtifactRequest.class);
        verify(artifactService).record(cap.capture(), eq(100L));
        ReportArtifactRequest req = cap.getValue();
        assertEquals(55L, req.getDispatchId());
        assertEquals(200L, req.getWorkitemId());
        assertEquals("patch.diff", req.getName());
        assertEquals("PATCH", req.getType());
        assertEquals("oss://x", req.getOssRef());
        assertEquals(1024L, req.getSize());
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
        when(dispatchService.onResult(100L, 1L, 55L, true, null, null, false, true)).thenReturn(true);
        when(dispatchService.mayRouteHandoff(100L, 1L, 55L)).thenReturn(true);

        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"workitemId\":200," +
                        "\"success\":true,\"checkpointReceiptVersion\":1,\"checkpointSeq\":7,\"checkpointSha256\":\"sha256:abc\"," +
                        "\"handoff\":{\"to\":\"AW_CR\",\"toType\":\"AGENT\"}}");

        verify(dispatchService).onResult(100L, 1L, 55L, true, null, null, false, true);
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
                any(), any(), anyBoolean());
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
        when(dispatchService.onResult(100L, 1L, 55L, true, "reply", null, false)).thenReturn(true);

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
        when(dispatchService.onResult(100L, 1L, 55L, true, null, null, false)).thenReturn(true);
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
        when(dispatchService.onResult(100L, 1L, 55L, false, null, null, false)).thenReturn(true);
        router.route(sessionWithBasic(1L, 10L, 100L, basicRemote),
                "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"workitemId\":200," +
                        "\"success\":false,\"handoff\":{\"to\":\"AW_CR\",\"toType\":\"AGENT\"}}");

        verify(dispatchService).onResult(100L, 1L, 55L, false, null, null, false);
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
    void heartbeatAccepted_triggersPersistHeartbeat() {
        ExecutorService executorService = mock(ExecutorService.class);
        InboundFrameRouter routerWithExecSvc = new InboundFrameRouter(dispatchService, artifactService,
                presenceManager, handoffService, drainScheduler, pauseService, guidanceService,
                null, null, null, null, executorService);
        when(presenceManager.heartbeat(1L, 10L, 1)).thenReturn(true);

        routerWithExecSvc.route(session(1L, 10L, 100L), "{\"type\":\"HEARTBEAT\"}");

        verify(executorService).persistHeartbeatIfNeeded(1L, 100L);
        verify(dispatchService).renewActiveLeases(100L, 1L, java.util.List.of());
    }

    @Test
    void heartbeatRejected_skipsPersistHeartbeat() throws Exception {
        ExecutorService executorService = mock(ExecutorService.class);
        InboundFrameRouter routerWithExecSvc = new InboundFrameRouter(dispatchService, artifactService,
                presenceManager, handoffService, drainScheduler, pauseService, guidanceService,
                null, null, null, null, executorService);
        Session ws = mock(Session.class);
        ExecutorSession es = new ExecutorSession(1L, 10L, 100L, ws);
        when(presenceManager.heartbeat(1L, 10L, 1)).thenReturn(false);

        routerWithExecSvc.route(es, "{\"type\":\"HEARTBEAT\"}");

        verify(executorService, never()).persistHeartbeatIfNeeded(anyLong(), anyLong());
        verify(ws).close();
    }

    @Test
    void heartbeatAccepted_nullExecutorService_doesNotFail() {
        when(presenceManager.heartbeat(1L, 10L, 1)).thenReturn(true);

        assertDoesNotThrow(() ->
                router.route(session(1L, 10L, 100L), "{\"type\":\"HEARTBEAT\"}"));
        verify(dispatchService).renewActiveLeases(100L, 1L, java.util.List.of());
    }
}
