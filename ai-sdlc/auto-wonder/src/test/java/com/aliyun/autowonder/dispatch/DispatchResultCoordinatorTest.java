package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.guidance.InteractionWorkflowService;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;
import java.util.function.Supplier;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchResultCoordinatorTest {
    DispatchResultStore store = mock(DispatchResultStore.class);
    DispatchDao dao = mock(DispatchDao.class);
    DispatchService dispatches = mock(DispatchService.class);
    DispatchCheckpointService checkpoints = mock(DispatchCheckpointService.class);
    DispatchPauseService pauses = mock(DispatchPauseService.class);
    DispatchRecoveryService recovery = mock(DispatchRecoveryService.class);
    HandoffService handoffs = mock(HandoffService.class);
    GuidanceService guidance = mock(GuidanceService.class);
    InteractionWorkflowService workflows = mock(InteractionWorkflowService.class);
    ScheduledTaskCapabilityGuard scheduled = mock(ScheduledTaskCapabilityGuard.class);
    DispatchResultCoordinator coordinator;
    JSONObject input;
    DispatchDO dispatch;
    static final String RESULT_ID = "b55aa1a7-43d5-4452-b39a-278554b67942";
    @BeforeEach void setup() {
        coordinator = new DispatchResultCoordinator(store, dao, dispatches, checkpoints, pauses,
                recovery, handoffs, guidance, workflows, scheduled);
        when(store.transaction(any())).thenAnswer(call -> ((Supplier<?>)call.getArgument(0)).get());
        input = JSON.parseObject("{\"type\":\"TASK_RESULT\",\"resultProtocolVersion\":2,\"resultId\":\""
                + RESULT_ID + "\",\"dispatchId\":55,\"success\":true,\"checkpointSeq\":7,\"checkpointSha256\":\"sha256:abc\"}");
        dispatch = new DispatchDO(); dispatch.setId(55L); dispatch.setTenantId(100L);
        dispatch.setExecutorId(1L); dispatch.setWorkitemId(200L); dispatch.setStatus("RUNNING");
        when(dao.findById(55L)).thenReturn(dispatch);
    }
    @Test void replayReturnsOriginalAcceptedDecisionAfterCheckpointWasPruned() {
        JSONObject ack = JSON.parseObject("{\"disposition\":\"ACCEPTED\",\"accepted\":true,\"retryable\":false}");
        when(store.find(100,55,RESULT_ID)).thenReturn(new DispatchResultStore.Decision(1,
                DispatchResultCoordinator.payloadHash(input),ack,null));
        assertSame(ack, coordinator.handle(100,1,input).ack());
        verifyNoInteractions(checkpoints,dispatches,handoffs,dao,recovery);
    }
    @Test void unavailableSchemaReturnsRetryableWithoutTerminalMutation() {
        doThrow(new IllegalStateException("schema missing")).when(store).requireReady();
        JSONObject ack = coordinator.handle(100,1,input).ack();
        assertEquals("RETRYABLE",ack.getString("disposition"));
        assertEquals(RESULT_ID,ack.getString("resultId"));
        assertTrue(ack.getBoolean("retryable"));
        verifyNoInteractions(dao,dispatches,handoffs);
    }
    @Test void invalidCheckpointPersistsPlatformFailureBeforeRejection() {
        when(dispatches.rejectProtocolResult(100,1,55,"COMPLETION_CHECKPOINT_INVALID")).thenReturn(true);
        JSONObject ack = coordinator.handle(100,1,input).ack();
        assertEquals("REJECTED",ack.getString("disposition"));
        var order = inOrder(dispatches,store);
        order.verify(dispatches).rejectProtocolResult(100,1,55,"COMPLETION_CHECKPOINT_INVALID");
        order.verify(store).insert(eq(100L),eq(1L),eq(input),anyString(),eq(ack),isNull());
        verifyNoInteractions(handoffs);
    }
    @Test void failedPlatformRejectionRemainsRetryable() {
        assertEquals("RETRYABLE",coordinator.handle(100,1,input).ack().getString("disposition"));
        verify(store,never()).insert(anyLong(),anyLong(),any(),any(),any(),any());
    }
    @Test void handoffExceptionCannotProduceAnAcceptedDecision() {
        allowCompletion();
        input.put("handoff",JSON.parseObject("{\"to\":\"qa\",\"toType\":\"AGENT\"}"));
        when(dispatches.onResult(100L,1L,55,true,null,null,false,true,null)).thenReturn(true);
        when(dispatches.mayRouteHandoff(100,1,55)).thenReturn(true);
        when(handoffs.handle(100,200,55,"qa","AGENT")).thenThrow(new IllegalStateException("db unavailable"));
        assertEquals("RETRYABLE",coordinator.handle(100,1,input).ack().getString("disposition"));
        verify(store,never()).insert(anyLong(),anyLong(),any(),any(),any(),any());
    }
    @Test void handoffUsesDurableOwnerAndPersistsOutcomeBeforeAcknowledging() {
        allowCompletion();
        input.put("workitemId",999L);
        input.put("handoff",JSON.parseObject("{\"to\":\"qa\",\"toType\":\"AGENT\"}"));
        when(dispatches.onResult(100L,1L,55,true,null,null,false,true,null)).thenReturn(true);
        when(dispatches.mayRouteHandoff(100,1,55)).thenReturn(true);
        when(handoffs.handle(100,200,55,"qa","AGENT")).thenReturn(HandoffResult.agent(7,56));
        var result = coordinator.handle(100,1,input);
        assertEquals("ACCEPTED",result.ack().getString("disposition"));
        assertEquals(56,result.handoff().getLongValue("downstreamDispatchId"));
        var order=inOrder(store,handoffs);
        order.verify(store).lockSubject(dispatch);
        order.verify(store).lockDispatch(100,55);
        order.verify(handoffs).handle(100,200,55,"qa","AGENT");
        order.verify(store).insert(eq(100L),eq(1L),eq(input),anyString(),eq(result.ack()),eq(result.handoff()));
    }
    @Test void staleExecutorGetsDurableRejectionWithoutTerminalizingCurrentOwner() {
        assertEquals("REJECTED",coordinator.handle(100,2,input).ack().getString("disposition"));
        verifyNoInteractions(checkpoints,dispatches,handoffs);
        verify(store).insert(eq(100L),eq(2L),eq(input),anyString(),any(),isNull());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void delayedSuccessPreservesReleasedPauseAndDoesNotReplayOldHandoff(boolean legacy) {
        if (legacy) { input.remove("resultProtocolVersion"); input.remove("resultId"); }
        dispatch.setStatus(DispatchStatus.PAUSED);
        input.put("handoff", JSON.parseObject("{\"to\":\"qa\",\"toType\":\"AGENT\"}"));
        input.put("workflowPlan", JSON.parseObject("{\"targetAgentId\":9}"));
        JSONObject ack = coordinator.handle(100, 1, input).ack();
        assertEquals("REJECTED", ack.getString("disposition"));
        assertEquals("DISPATCH_PAUSED", ack.getString("reasonCode"));
        assertFalse(ack.getBoolean("retryable"));
        verify(store).insert(eq(100L), eq(1L), any(), anyString(), eq(ack), isNull());
        verifyNoInteractions(checkpoints, pauses, dispatches, handoffs, guidance, workflows);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void delayedFailureCannotRestartOrFailReleasedPause(boolean legacy) {
        if (legacy) { input.remove("resultProtocolVersion"); input.remove("resultId"); }
        dispatch.setStatus(DispatchStatus.PAUSED);
        input.put("success", false);
        input.put("failureCategory", "runtime_recovery");
        JSONObject ack = coordinator.handle(100, 1, input).ack();
        assertEquals("DISPATCH_PAUSED", ack.getString("reasonCode"));
        verify(store).insert(eq(100L), eq(1L), any(), anyString(), eq(ack), isNull());
        verifyNoInteractions(checkpoints, pauses, dispatches, handoffs, guidance, workflows);
    }
    @Test void canonicalHashIgnoresObjectOrderButProtectsPayload() {
        JSONObject reordered=JSON.parseObject("{\"workflowPlan\":{\"z\":2,\"a\":1},\"resultSummary\":\"done\"}");
        JSONObject original=JSON.parseObject("{\"resultSummary\":\"done\",\"workflowPlan\":{\"a\":1,\"z\":2}}");
        assertEquals(DispatchResultCoordinator.payloadHash(original),DispatchResultCoordinator.payloadHash(reordered));
        reordered.put("resultSummary","changed");
        assertNotEquals(DispatchResultCoordinator.payloadHash(original),DispatchResultCoordinator.payloadHash(reordered));
    }
    @Test void optionalDebugLogCannotChangeBusinessIdentity() {
        String original=DispatchResultCoordinator.payloadHash(input);
        input.put("debugLog",JSON.parseObject("{\"status\":\"UPLOADED\",\"sha256\":\"abc\"}"));
        assertEquals(original,DispatchResultCoordinator.payloadHash(input));
        input.put("resultSummary","different business result");
        assertNotEquals(original,DispatchResultCoordinator.payloadHash(input));
    }
    @Test void failureHistoryDiagnosticsCannotChangeBusinessIdentityOrRouting() {
        String original=DispatchResultCoordinator.payloadHash(input);
        input.put("failureHistory",JSON.parseObject("{\"rounds\":[{\"phase\":\"routing_preflight\","
                + "\"attemptIndex\":1,\"sessionId\":\"689186f5-a6bd-4816-9fa1-6bff68e7b896\",\"status\":\"failed\","
                + "\"error\":\"round one\",\"diagnostics\":[{\"source\":\"provider_log\",\"code\":\"10605\","
                + "\"subCode\":\"model_queued\",\"message\":\"Queuing failed\","
                + "\"requestId\":\"f203f510-c793-4e82-b64d-18c879abb08c\"}]}],\"truncated\":false}"));
        assertEquals(original,DispatchResultCoordinator.payloadHash(input));
        input.put("success",false); input.put("failureCategory","tool_hook_blocked");
        input.put("error","workflow routing preflight failed: qoder semantic inactivity timeout after 10m0s");
        when(dispatches.onResult(100L,1L,55,false,null,input.getString("error"),false,false,"tool_hook_blocked")).thenReturn(true);
        assertEquals("ACCEPTED",coordinator.handle(100,1,input).ack().getString("disposition"));
        verify(dispatches,never()).onExecutorUnavailableResult(anyLong(),anyLong(),anyLong(),any(),any(),anyLong());
        verify(store).insert(eq(100L),eq(1L),eq(input),eq(DispatchResultCoordinator.payloadHash(input)),any(),isNull());
    }
    @Test void failedResultPreservesCategoryWithoutCheckpointOrHandoff() {
        input.put("success",false); input.put("failureCategory","tool_hook_blocked");
        input.put("error","usage limit is tool output, not provider metadata");
        when(dispatches.onResult(100L,1L,55,false,null,input.getString("error"),false,false,"tool_hook_blocked")).thenReturn(true);
        assertEquals("ACCEPTED",coordinator.handle(100,1,input).ack().getString("disposition"));
        verifyNoInteractions(checkpoints,handoffs);
        verify(dispatches,never()).onExecutorUnavailableResult(anyLong(),anyLong(),anyLong(),any(),any(),anyLong());
    }
    @Test void failoverUsesOnlyCommittedDecisionBudget() {
        input.put("success",false); input.put("failureCategory","runtime_recovery");
        when(store.countSessionRecoveryDecisions(100,55)).thenReturn(1L);
        when(dispatches.onExecutorUnavailableResult(100,1,55,"runtime_recovery",null,1)).thenReturn(true);
        assertEquals("ACCEPTED",coordinator.handle(100,1,input).ack().getString("disposition"));
        verify(dispatches).onExecutorUnavailableResult(100,1,55,"runtime_recovery",null,1);
        verify(guidance).requeueForExecutorFailover(100,55);
        verifyNoInteractions(checkpoints,handoffs);
    }
    @Test void completionWhilePausingCommitsReworkWithoutOrdinaryResult() {
        allowCompletion();
        when(pauses.onCompletedWhilePausing(100,1,55,7,"sha256:abc"))
                .thenReturn(DispatchPauseService.CompletionDisposition.PAUSED);
        assertEquals("ACCEPTED",coordinator.handle(100,1,input).ack().getString("disposition"));
        verify(guidance).requeueDeliveredForDispatch(100,55);
        verify(workflows).onPaused(100,55);
        verifyNoInteractions(dispatches,handoffs);
    }
    @Test void failureWhilePausingRejectsResultAndDefersWaiterReleaseAfterCommit() {
        dispatch.setStatus(DispatchStatus.PAUSING);
        input.put("success", false);
        when(dispatches.onResult(100L, 1L, 55, false, null, null, false, false, null)).thenReturn(false);
        when(dispatches.rejectProtocolResult(100, 1, 55, "RESULT_STATE_CONFLICT")).thenReturn(true);
        when(dao.existsWaitingForPredecessor(100L, 55L)).thenReturn(true);

        JSONObject ack = coordinator.handle(100, 1, input).ack();

        assertEquals("REJECTED", ack.getString("disposition"));
        assertEquals("RESULT_STATE_CONFLICT", ack.getString("reasonCode"));
        var order = inOrder(store, workflows);
        order.verify(store).insert(eq(100L), eq(1L), eq(input), anyString(), eq(ack), isNull());
        order.verify(workflows).reconcileWaitersForPredecessor(100L, 55L);
    }

    @Test void acceptedTerminalFailureAlsoDefersWaiterRelease() {
        input.put("success", false);
        when(dispatches.onResult(100L, 1L, 55, false, null, null, false, false, null)).thenReturn(true);
        when(dao.existsWaitingForPredecessor(100L, 55L)).thenReturn(true);

        assertEquals("ACCEPTED", coordinator.handle(100, 1, input).ack().getString("disposition"));

        verify(workflows).reconcileWaitersForPredecessor(100L, 55L);
    }

    @Test void workflowPlanIsAppliedBeforeDecisionPersistence() {
        allowCompletion();
        JSONObject plan=JSON.parseObject("{\"targetAgentId\":9}");input.put("workflowPlan",plan);
        when(dispatches.onResult(100L,1L,55,true,null,null,false,false,null)).thenReturn(true);
        assertEquals("ACCEPTED",coordinator.handle(100,1,input).ack().getString("disposition"));
        var order=inOrder(workflows,store);
        order.verify(workflows).apply(100,55,plan);
        order.verify(store).insert(eq(100L),eq(1L),eq(input),anyString(),any(),isNull());
    }
    @Test void canceledExecutionIsResolvedBeforeItsRejectedDecision() {
        when(recovery.cancelRequested(100,55)).thenReturn(true);
        when(recovery.onStopped(100,1,55)).thenReturn(true);
        assertEquals("REJECTED",coordinator.handle(100,1,input).ack().getString("disposition"));
        var order=inOrder(recovery,store);
        order.verify(recovery).onStopped(100,1,55);
        order.verify(store).insert(eq(100L),eq(1L),eq(input),anyString(),any(),isNull());
        verifyNoInteractions(checkpoints,dispatches,handoffs);
    }

    @Test void legacyResultIsAcceptedAndReplayedWithoutV2CheckpointIdentity() {
        JSONObject legacy = JSON.parseObject("{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true,"
                + "\"resultSummary\":\"done\",\"checkpointReceiptVersion\":1,\"checkpointSeq\":7,"
                + "\"checkpointSha256\":\"sha256:old\"}");
        when(dispatches.onResult(100L,1L,55,true,"done",null,false,false,null)).thenReturn(true);

        var first = coordinator.handle(100,1,legacy);

        assertTrue(first.ack().getBoolean("accepted"));
        assertEquals("ACCEPTED", first.ack().getString("disposition"));
        assertTrue(DispatchResultCoordinator.validResultId(first.ack().getString("resultId")));
        ArgumentCaptor<JSONObject> inserted = ArgumentCaptor.forClass(JSONObject.class);
        verify(store).insert(eq(100L),eq(1L),inserted.capture(),anyString(),eq(first.ack()),isNull());
        String stableId = inserted.getValue().getString("resultId");
        assertTrue(DispatchResultCoordinator.validResultId(stableId));
        assertEquals(2, inserted.getValue().getIntValue("resultProtocolVersion"));
        verify(checkpoints,never()).matchesCompletionReceipt(anyLong(),anyLong(),anyString(),anyLong(),anyString());

        when(store.find(100,55,stableId)).thenReturn(new DispatchResultStore.Decision(1,
                DispatchResultCoordinator.payloadHash(inserted.getValue()), first.ack(), null));
        var replay = coordinator.handle(100,1,legacy);

        assertSame(first.ack(), replay.ack());
        verify(dispatches,times(1)).onResult(100L,1L,55,true,"done",null,false,false,null);
    }

    @Test void legacyCompletionCannotBypassAnExistingPauseIntent() {
        input.remove("resultProtocolVersion"); input.remove("resultId");
        dispatch.setStatus("PAUSING");
        when(pauses.onCompletedWhilePausing(100,1,55,7,"sha256:abc"))
                .thenReturn(DispatchPauseService.CompletionDisposition.REJECTED);
        when(dispatches.rejectProtocolResult(100,1,55,"COMPLETION_PAUSE_REJECTED")).thenReturn(true);
        assertEquals("REJECTED",coordinator.handle(100,1,input).ack().getString("disposition"));
        verify(dispatches,never()).onResult(anyLong(),anyLong(),anyLong(),anyBoolean(),any(),any(),anyBoolean(),anyBoolean(),any());
    }

    @Test void legacyFailoverDoesNotShadowLaterCompletionOnTheSameExecutor() {
        var decisions = new java.util.HashMap<String, DispatchResultStore.Decision>();
        when(store.find(eq(100L),eq(55L),anyString())).thenAnswer(call -> decisions.get(call.getArgument(2)));
        doAnswer(call -> {
            JSONObject frame = call.getArgument(2);
            decisions.put(frame.getString("resultId"),new DispatchResultStore.Decision(1,
                    call.getArgument(3),call.getArgument(4),call.getArgument(5)));
            return null;
        }).when(store).insert(anyLong(),anyLong(),any(),anyString(),any(),any());
        JSONObject failure=JSON.parseObject("{\"dispatchId\":55,\"success\":false,\"failureCategory\":\"runtime_recovery\"}");
        when(dispatches.onExecutorUnavailableResult(100,1,55,"runtime_recovery",null,0)).thenReturn(true);
        assertTrue(coordinator.handle(100,1,failure).ack().getBooleanValue("accepted"));
        JSONObject completed=JSON.parseObject("{\"dispatchId\":55,\"success\":true}");
        when(dispatches.onResult(100L,1L,55,true,null,null,false,false,null)).thenReturn(true);
        assertTrue(coordinator.handle(100,1,completed).ack().getBooleanValue("accepted"));
        assertTrue(coordinator.handle(100,1,completed).ack().getBooleanValue("accepted"));
        verify(dispatches,times(1)).onResult(100L,1L,55,true,null,null,false,false,null);
        assertEquals(2,decisions.size());
    }

    @Test void partialV2IdentityDoesNotDowngradeToLegacyCompatibility() {
        JSONObject malformed = JSON.parseObject("{\"type\":\"TASK_RESULT\",\"dispatchId\":55,"
                + "\"success\":true,\"resultProtocolVersion\":2}");

        var result = coordinator.handle(100,1,malformed);

        assertEquals("RETRYABLE",result.ack().getString("disposition"));
        assertEquals("INVALID_RESULT_IDENTITY",result.ack().getString("reasonCode"));
        verifyNoInteractions(store,dao,dispatches,checkpoints,handoffs);
    }

    private void allowCompletion() {
        when(checkpoints.matchesCompletionReceipt(100,55,RESULT_ID,7,"sha256:abc")).thenReturn(true);
    }
}
