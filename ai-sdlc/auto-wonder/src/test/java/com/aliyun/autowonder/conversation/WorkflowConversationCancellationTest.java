package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.*;
import com.aliyun.autowonder.squad.workflow.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkflowConversationCancellationTest {
    final AgentConversationDao conversations = mock(AgentConversationDao.class);
    final AgentConversationTurnDao turns = mock(AgentConversationTurnDao.class);
    final ConversationTransport transport = mock(ConversationTransport.class);
    final ConversationRuntimePresence presence = mock(ConversationRuntimePresence.class);
    final SquadWorkflowDao workflows = mock(SquadWorkflowDao.class);
    final AgentConversationDO conv = new AgentConversationDO();
    final AgentConversationTurnDO turn = new AgentConversationTurnDO();
    final SquadWorkflowDO row = new SquadWorkflowDO();
    AgentConversationService service;

    AgentConversationService newService() {
        var result = new AgentConversationService(conversations, turns, transport,
                mock(AgentDao.class), mock(AgentVersionDao.class),
                mock(ConversationChannelSinkRegistry.class), presence, mock(ConversationExecutorRouter.class));
        result.setWorkflowDao(workflows);
        return result;
    }
    @BeforeEach void setup() {
        service = newService();
        conv.setId(77L); conv.setTenantId(1L); conv.setExecutorId(9L);
        conv.setChannel(SquadWorkflowChannelSink.CHANNEL); conv.setBizRefId(42L);
        turn.setId(55L); turn.setTenantId(1L); turn.setConversationId(77L);
        turn.setDirection("IN"); turn.setStatus("PROCESSING"); turn.setLastDispatchAt(new Date(1000));
        row.setId(42L); row.setConversationId(77L); row.setStatus("FAILED");
        row.setDeadlineAt(new Date(System.currentTimeMillis() + 1200000)); row.setCompletedAt(new Date(2000));
        when(turns.listActiveWorkflowTurns(anyLong(), eq(100))).thenReturn(List.of(turn));
        when(turns.acquireConversationLock(anyString(), anyInt())).thenReturn(1);
        when(conversations.findById(1L,77L)).thenReturn(conv);
        when(turns.findByConversationTurn(1L,77L,55L)).thenReturn(turn);
        when(workflows.findById(1L,42L)).thenReturn(row);
        when(presence.isExecutorOnline(9L)).thenReturn(true);
        when(presence.supportsProtocolFeature(9L,"CONVERSATION_TURN_CANCEL")).thenReturn(true);
        when(presence.hasConversationTurnActivityReport(9L)).thenReturn(true);
        when(presence.activeConversationTurnIds(9L)).thenReturn(Set.of(55L));
        begin();
    }
    void begin() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    void commit() {
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        callbacks.forEach(c -> c.afterCommit());
        callbacks.forEach(c -> c.afterCompletion(0));
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        begin();
    }
    @AfterEach void cleanup() {
        if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    @Test void cancelOnlyAfterCommitAndRetryLostAckAfterServerRestart() {
        service.cancelInactiveWorkflowTurns();
        verify(transport, never()).sendCancel(any(),anyLong());
        commit();
        verify(transport).sendCancel(conv,55L);
        service = newService();
        service.cancelInactiveWorkflowTurns(); commit();
        verify(transport,times(2)).sendCancel(conv,55L);
        verify(turns,never()).updateInboundStatusIfProcessing(anyLong(),anyLong(),anyLong(),any(),any());
    }
    @Test void failedSendIsRetriedWithoutDroppingDurableIntent() {
        doThrow(new IllegalStateException("disconnected")).doNothing().when(transport).sendCancel(conv,55L);
        service.cancelInactiveWorkflowTurns(); commit();
        service.cancelInactiveWorkflowTurns(); commit();
        verify(transport,times(2)).sendCancel(conv,55L);
    }
    @Test void liveAttemptIsNotCanceled() {
        row.setStatus("GENERATING");
        service.cancelInactiveWorkflowTurns(); commit();
        verify(transport,never()).sendCancel(any(),anyLong());
    }
    @Test void expiredAttemptIsCanceledBeforeSnapshotSweepMarksItFailed() {
        row.setStatus("GENERATING"); row.setDeadlineAt(new Date(0));
        service.cancelInactiveWorkflowTurns(); commit();
        verify(transport).sendCancel(conv,55L);
    }
    @Test void supersededConversationIsCanceledWithoutTouchingNewAttempt() {
        row.setStatus("GENERATING"); row.setConversationId(78L);
        service.cancelInactiveWorkflowTurns(); commit();
        verify(transport).sendCancel(conv,55L);
        verify(workflows,never()).update(any());
    }
    @Test void readySnapshotAlsoStopsAnyLingeringTurn() {
        row.setStatus("READY"); service.cancelInactiveWorkflowTurns(); commit();
        verify(transport).sendCancel(conv,55L);
        verify(workflows,never()).update(any());
    }
    @Test void offlineAndLegacyRuntimesRemainFencedUntilTheyCanStop() {
        when(presence.isExecutorOnline(9L)).thenReturn(false);
        service.cancelInactiveWorkflowTurns(); commit();
        when(presence.isExecutorOnline(9L)).thenReturn(true);
        when(presence.supportsProtocolFeature(9L,"CONVERSATION_TURN_CANCEL")).thenReturn(false);
        service.cancelInactiveWorkflowTurns(); commit();
        verify(transport,never()).sendCancel(any(),anyLong());
        verify(turns,never()).updateInboundStatusIfProcessing(anyLong(),anyLong(),anyLong(),any(),any());
        when(presence.supportsProtocolFeature(9L,"CONVERSATION_TURN_CANCEL")).thenReturn(true);
        service.cancelInactiveWorkflowTurns(); commit();
        verify(transport).sendCancel(conv,55L);
    }
    @Test void confirmedInactiveRuntimeConvergesLocallyWithoutResending() {
        turn.setSourceContext("{\"workflowCancelSentAt\":2000}");
        when(presence.isConversationTurnInactiveAfter(9L,55L,2000L)).thenReturn(true);
        when(turns.updateInboundStatusIfProcessing(1L,77L,55L,"CANCELED",null)).thenReturn(1);
        service.cancelInactiveWorkflowTurns(); commit();
        verify(turns).updateInboundStatusIfProcessing(1L,77L,55L,"CANCELED",null);
        verify(transport,never()).sendCancel(any(),anyLong());
    }
    @Test void oldHeartbeatAbsenceDoesNotDiscardTheCancelRequest() {
        when(presence.activeConversationTurnIds(9L)).thenReturn(Set.of());
        when(presence.isConversationTurnInactiveAfter(9L,55L,2000L)).thenReturn(false);
        service.cancelInactiveWorkflowTurns(); commit();
        verify(transport).sendCancel(conv,55L);
        verify(turns,never()).updateInboundStatusIfProcessing(anyLong(),anyLong(),anyLong(),any(),any());
    }
    @Test void evenNewerHeartbeatCannotAvoidFirstCancellationSend() {
        when(presence.isConversationTurnInactiveAfter(anyLong(),anyLong(),anyLong())).thenReturn(true);
        service.cancelInactiveWorkflowTurns(); commit();
        verify(transport).sendCancel(conv,55L);
        verify(turns).updateProcessingSourceContext(eq(1L),eq(55L),isNull(),contains("workflowCancelSentAt"));
        verify(turns,never()).updateInboundStatusIfProcessing(anyLong(),anyLong(),anyLong(),any(),any());
    }
    @Test void persistedCancelMarkerPreservesExistingLongContext() {
        turn.setSourceContext("{\"actorId\":9223372036854775806}");
        service.cancelInactiveWorkflowTurns(); commit();
        var context = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(turns).updateProcessingSourceContext(eq(1L), eq(55L),
                eq("{\"actorId\":9223372036854775806}"), context.capture());
        var persisted = com.aliyun.autowonder.json.JSON.parseObject(context.getValue());
        org.junit.jupiter.api.Assertions.assertEquals(Long.MAX_VALUE - 1, persisted.getLongValue("actorId"));
        org.junit.jupiter.api.Assertions.assertTrue(persisted.getLongValue("workflowCancelSentAt") > 0);
    }
    @Test void queuedTurnIsCanceledWithoutSendingToRuntime() {
        turn.setStatus("QUEUED");
        when(turns.updateStatusIfCurrent(1L,55L,"QUEUED","CANCELED",null)).thenReturn(1);
        service.cancelInactiveWorkflowTurns(); commit();
        verify(turns).updateStatusIfCurrent(1L,55L,"QUEUED","CANCELED",null);
        verify(transport,never()).sendCancel(any(),anyLong());
    }
    @Test void canceledAckStopsFurtherCancellationAttempts() {
        turn.setStatus("CANCELED");
        service.cancelInactiveWorkflowTurns(); commit();
        verify(transport,never()).sendCancel(any(),anyLong());
    }
    @Test void rollbackNeverSendsCancellation() {
        service.cancelInactiveWorkflowTurns();
        TransactionSynchronizationManager.getSynchronizations().forEach(c -> c.afterCompletion(1));
        verify(transport,never()).sendCancel(any(),anyLong());
    }
    @Test void fullPageAdvancesCursorSoUnresponsiveRuntimeCannotStarveNewerTurns() {
        var batch = new ArrayList<AgentConversationTurnDO>();
        for(int i=1;i<=100;i++) {
            var t = new AgentConversationTurnDO(); t.setId((long)i);t.setTenantId(1L);t.setConversationId(77L);batch.add(t);
        }
        when(turns.listActiveWorkflowTurns(0,100)).thenReturn(batch);
        when(turns.listActiveWorkflowTurns(100,100)).thenReturn(List.of());
        row.setStatus("GENERATING");
        service.cancelInactiveWorkflowTurns();commit();
        service.cancelInactiveWorkflowTurns();commit();
        service.cancelInactiveWorkflowTurns();commit();
        verify(turns,times(2)).listActiveWorkflowTurns(0,100);
        verify(turns).listActiveWorkflowTurns(100,100);
    }
}
