package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.*;
import com.aliyun.autowonder.json.JSON;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConversationQuestionExpiryTest {
    final AgentConversationDao convs = mock(AgentConversationDao.class);
    final AgentConversationTurnDao turns = mock(AgentConversationTurnDao.class);
    final AgentConversationElicitationDao questions = mock(AgentConversationElicitationDao.class);
    final ConversationTransport transport = mock(ConversationTransport.class);
    final ConversationRuntimePresence presence = mock(ConversationRuntimePresence.class);
    final ConversationBrowserEventPublisher browser = mock(ConversationBrowserEventPublisher.class);
    final AgentConversationService service = new AgentConversationService(convs, turns, transport,
            mock(AgentDao.class), mock(AgentVersionDao.class), mock(ConversationChannelSinkRegistry.class),
            presence, mock(ConversationExecutorRouter.class));
    final ConversationElicitationService elicitations = new ConversationElicitationService(questions, convs, transport, presence, turns);
    final ConversationAnswerDeliveryService delivery = new ConversationAnswerDeliveryService(questions, turns, convs, transport);
    final AgentConversationDO conv = new AgentConversationDO();
    final AgentConversationTurnDO turn = new AgentConversationTurnDO();
    final AgentConversationElicitationDO question = new AgentConversationElicitationDO();
    final Date cutoff = new Date(System.currentTimeMillis() - 30*60_000L);

    @BeforeEach void setup() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        conv.setId(10L); conv.setTenantId(1L); conv.setExecutorId(9L); conv.setChannel("PLATFORM_ASSISTANT");
        turn.setId(100L); turn.setTenantId(1L); turn.setConversationId(10L); turn.setDirection("IN");
        turn.setStatus("PROCESSING"); turn.setDispatchAttempt(2); turn.setSourceContext("{\"actor\":7}");
        turn.setLastDispatchAt(new Date(1));
        question.setId(11L); question.setTenantId(1L); question.setConversationId(10L); question.setTurnId(100L);
        question.setRequestId("q"); question.setStatus("PENDING"); question.setDispatchAttempt(2);
        question.setExecutorId(9L); question.setAnswerProtocolVersion(1); question.setGmtCreate(new Date(1000));
        question.setMessage("Original question"); question.setSchemaJson("{\"type\":\"object\"}");
        when(convs.findById(1L,10L)).thenReturn(conv);
        when(turns.acquireConversationLock(anyString(), anyInt())).thenReturn(1);
        when(turns.findByConversationTurnForUpdate(1L,10L,100L)).thenReturn(turn);
        when(turns.findByConversationTurn(1L,10L,100L)).thenReturn(turn);
        when(turns.updateProcessingSourceContext(eq(1L),eq(100L),any(),anyString())).thenReturn(1);
        when(turns.updateInboundStatusIfProcessing(eq(1L),eq(10L),eq(100L),anyString(),any())).thenReturn(1);
        when(questions.findByRequestIdForUpdate(1,10,"q")).thenReturn(question);
        when(questions.settleIfPending(1,10,"q","EXPIRED",null)).thenReturn(1);
        when(presence.isExecutorOnline(9L)).thenReturn(true);
        when(presence.supportsProtocolFeature(9L,"CONVERSATION_TURN_CANCEL")).thenReturn(true);
        service.setConversationElicitationService(elicitations); service.setBrowserEventPublisher(browser);
        elicitations.setBrowserEventPublisher(browser); elicitations.setAnswerDeliveryService(delivery);
        delivery.setExpiryCoordinator(service);
    }
    @AfterEach void cleanup() throws Exception {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        var field = AgentConversationService.class.getDeclaredField("cancelTimeoutScheduler"); field.setAccessible(true);
        ((ScheduledExecutorService)field.get(service)).shutdownNow();
    }
    void commit() {
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        callbacks.forEach(TransactionSynchronization::afterCommit);
        callbacks.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
    }
    void noAnswers() {
        verify(questions,never()).saveAnswerIfPending(any());
        verify(transport,never()).sendElicitationReply(any(),anyLong(),anyString(),anyString(),any());
        verify(transport,never()).sendReliableElicitationReply(any(),any(),anyLong());
    }

    @Test void expiryPersistsExactStopAndOnlySendsTurnCancelAfterCommit() {
        var other = new AgentConversationElicitationDO(); other.setTenantId(1L);other.setConversationId(10L);
        other.setTurnId(100L);other.setRequestId("other");other.setStatus("PENDING");
        when(questions.listPendingByTurn(1,100)).thenReturn(List.of(other));
        when(questions.settleIfPending(1,10,"other","CANCELED",null)).thenReturn(1);
        assertTrue(delivery.expireQuestion(question,cutoff));
        verifyNoInteractions(transport,browser);
        var order=inOrder(turns,questions);
        order.verify(turns).acquireConversationLock(anyString(),anyInt());
        order.verify(turns).findByConversationTurnForUpdate(1L,10L,100L);
        order.verify(questions).findByRequestIdForUpdate(1,10,"q");
        var context=JSON.parseObject(turn.getSourceContext());
        assertEquals(7,context.getIntValue("actor"));
        assertEquals(2,context.getJSONObject("questionExpiryStop").getIntValue("attempt"));
        assertEquals(9,context.getJSONObject("questionExpiryStop").getLongValue("executorId"));
        assertEquals("EXPIRED",question.getStatus()); assertNull(question.getAnswerId());
        assertNull(question.getDeliveryStatus()); assertEquals("Original question",question.getMessage());
        commit();
        verify(transport).sendCancelWithAttempt(argThat(c -> c.getExecutorId()==9L),eq(100L),eq(2));
        verify(browser).publishCommittedServerEvent(eq(1L),eq(10L),eq(100L),eq("acp_elicitation_resolved"),
                argThat(raw -> raw.contains("\"status\":\"EXPIRED\"") && !raw.contains("decline")));
        noAnswers();
    }
    @Test void stopContextRetainsLongIdentityAndRejectsInvalidJson() {
        long executorId = Long.MAX_VALUE - 2;
        long requestedAt = Long.MAX_VALUE - 1;
        var context = new com.aliyun.autowonder.json.JSONObject();
        context.put("questionExpiryStop", Map.of("attempt", 2,
                "executorId", executorId, "requestedAt", requestedAt));
        turn.setSourceContext(context.toJSONString());
        var stop = AgentConversationService.questionExpiryStop(turn);
        assertNotNull(stop);
        assertEquals(executorId, stop.getLongValue("executorId"));
        assertEquals(requestedAt, stop.getLongValue("requestedAt"));
        turn.setDispatchAttempt(3);
        assertNull(AgentConversationService.questionExpiryStop(turn));
        turn.setDispatchAttempt(2);
        for (String invalid : List.of("{", "[]", "null")) {
            turn.setSourceContext(invalid);
            assertNull(AgentConversationService.questionExpiryStop(turn));
        }
    }
    @Test void rollbackEmitsNoRuntimeOrBrowserFrames() {
        assertTrue(delivery.expireQuestion(question,cutoff));
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(transport,browser); noAnswers();
    }
    @ParameterizedTest @ValueSource(strings={"answer","decline","terminal","attempt","executor","age","claim"})
    void staleCandidateNeverStopsWinner(String winner) {
        var snapshot = new AgentConversationElicitationDO();
        org.springframework.beans.BeanUtils.copyProperties(question,snapshot);
        switch(winner) {
            case "answer" -> question.setStatus("ANSWERED");
            case "decline" -> question.setStatus("DECLINED");
            case "terminal" -> turn.setStatus("SUCCESS");
            case "attempt" -> turn.setDispatchAttempt(3);
            case "executor" -> conv.setExecutorId(99L);
            case "age" -> question.setGmtCreate(new Date());
            case "claim" -> when(questions.settleIfPending(1,10,"q","EXPIRED",null)).thenReturn(0);
        }
        assertFalse(delivery.expireQuestion(snapshot,cutoff)); commit();
        verify(turns,never()).updateProcessingSourceContext(anyLong(),anyLong(),any(),anyString());
        verifyNoInteractions(transport,browser); noAnswers();
    }
    @ParameterizedTest @ValueSource(strings={"offline","unsupported","throwing"})
    void failedStopDeliveryStillExpiresAndSchedulesBoundedCleanup(String mode) throws Exception {
        if(mode.equals("offline")) when(presence.isExecutorOnline(9L)).thenReturn(false);
        if(mode.equals("unsupported")) when(presence.supportsProtocolFeature(9L,"CONVERSATION_TURN_CANCEL")).thenReturn(false);
        if(mode.equals("throwing")) doThrow(new IllegalStateException("offline")).when(transport).sendCancelWithAttempt(any(),eq(100L),eq(2));
        assertTrue(delivery.expireQuestion(question,cutoff)); commit();
        var field=AgentConversationService.class.getDeclaredField("pendingCancels");field.setAccessible(true);
        assertTrue(((Map<?,?>)field.get(service)).containsKey(100L));
        service.handleQuestionExpiryTimeout(conv,100L,2); commit();
        verify(turns).updateInboundStatusIfProcessing(1L,10L,100L,"CANCELED",null);
        noAnswers();
    }
    @Test void oldTimeoutCannotCancelLaterAttempt() {
        assertTrue(delivery.expireQuestion(question,cutoff)); commit();
        turn.setDispatchAttempt(3);
        service.handleQuestionExpiryTimeout(conv,100L,2); commit();
        verify(turns,never()).updateInboundStatusIfProcessing(anyLong(),anyLong(),anyLong(),anyString(),any());
    }
    @Test void expiredAttemptCannotBecomeSuccessWhenAckArrives() {
        assertTrue(delivery.expireQuestion(question,cutoff)); commit();
        service.acknowledgeTurn(1L,9L,10L,100L,"SUCCESS",null,"partial real output","new-session",null,2); commit();
        verify(turns).updateInboundStatusIfProcessing(1L,10L,100L,"CANCELED",null);
        verify(convs,never()).updateCliSessionRef(anyLong(),anyLong(),anyString());
        var out=org.mockito.ArgumentCaptor.forClass(AgentConversationTurnDO.class);
        verify(turns).insert(out.capture()); assertEquals("partial real output",out.getValue().getContent());
        assertEquals("QUESTION_EXPIRED",ConversationTurnDisplay.project(conv.getChannel(),out.getValue().getSourceContext()).error().code());
    }
    @Test void ackUsesLockingStopIntentEvenWhenEarlierConsistentReadIsStale() {
        var stale=new AgentConversationTurnDO();org.springframework.beans.BeanUtils.copyProperties(turn,stale);
        when(turns.findByConversationTurn(1L,10L,100L)).thenReturn(stale);
        assertTrue(delivery.expireQuestion(question,cutoff));commit();
        service.acknowledgeTurn(1L,9L,10L,100L,"SUCCESS",null,null,null,null,2);commit();
        var out=org.mockito.ArgumentCaptor.forClass(AgentConversationTurnDO.class);
        verify(turns).insert(out.capture());
        var display=ConversationTurnDisplay.project(conv.getChannel(),out.getValue().getSourceContext());
        assertNotNull(display,"expiry reason must come from the locking read, not an older transaction snapshot");
        assertEquals("QUESTION_EXPIRED",display.content().code());
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void restartRecoveryNeverRedispatchesExpiredAttempt(boolean active) {
        turn.setSourceContext("{\"questionExpiryStop\":{\"attempt\":2,\"executorId\":9,\"requestedAt\":1}}");
        when(turns.listStaleProcessingInboundByExecutor(eq(1L),eq(9L),any(),eq(100))).thenReturn(List.of(turn));
        service.recoverStaleTurnsForExecutor(1L,9L,active?Set.of(100L):Set.of(),true,new Date(),100); commit();
        verify(turns).updateInboundStatusIfProcessing(1L,10L,100L,"CANCELED",null);
        verify(transport).sendCancelWithAttempt(any(),eq(100L),eq(2));
        verify(transport,never()).send(any(),anyLong(),anyString(),anyString(),any()); noAnswers();
    }
    @ParameterizedTest @ValueSource(strings={"same-second","new-attempt","unknown"})
    void legacyIdentityRequiresDispatchEvidence(String timing) {
        question.setAnswerProtocolVersion(null);question.setDispatchAttempt(null);question.setExecutorId(null);
        turn.setLastDispatchAt(timing.equals("unknown")?null:new Date(timing.equals("same-second")?1999:2000));
        assertEquals(timing.equals("same-second"),delivery.expireQuestion(question,cutoff)); commit(); noAnswers();
    }
    @Test void stopIntentPersistenceFailureCannotEmitFrames() {
        when(turns.updateProcessingSourceContext(anyLong(),anyLong(),any(),anyString())).thenReturn(0);
        assertThrows(IllegalStateException.class,()->delivery.expireQuestion(question,cutoff));
        verifyNoInteractions(transport,browser);
    }
    @Test void lateQuestionAfterStopIsStoredSettledAndPublishedOnlyAfterCommit() {
        assertTrue(delivery.expireQuestion(question,cutoff));commit(); clearInvocations(browser,transport);
        when(questions.insertIfAbsent(any())).thenReturn(1);
        elicitations.onEvent(1,10,100,"acp_elicitation","{\"data\":{\"requestId\":\"late\",\"message\":\"late original\",\"requestedSchema\":{\"type\":\"object\"},\"answerProtocolVersion\":1}}");
        var inserted=org.mockito.ArgumentCaptor.forClass(AgentConversationElicitationDO.class);
        verify(questions).insertIfAbsent(inserted.capture());
        assertEquals("CANCELED",inserted.getValue().getStatus());
        assertEquals("late original",inserted.getValue().getMessage());
        verifyNoInteractions(browser,transport);commit();
        verify(browser).publishCommittedServerEvent(eq(1L),eq(10L),eq(100L),eq("acp_elicitation_resolved"),contains("CANCELED"));
        verifyNoInteractions(transport);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void lateAnswerCannotEnterStoppedAttempt(boolean reliable) {
        assertTrue(delivery.expireQuestion(question,cutoff));commit();
        question.setStatus("PENDING"); question.setGmtCreate(new Date());
        question.setAnswerProtocolVersion(reliable?1:null);
        when(questions.findByRequestId(1,10,"q")).thenReturn(question);
        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                ()->elicitations.reply(1,10,"q","accept","{}"));
        verify(questions,never()).saveAnswerIfPending(any());
        verify(questions,never()).settleIfPending(1,10,"q","ANSWERED","{}"); noAnswers();
    }
    @Test void savedAnswerCannotDeliverAfterSystemStop() {
        assertTrue(delivery.expireQuestion(question,cutoff));commit(); clearInvocations(transport);
        question.setStatus("ANSWERED");question.setAnswerId("a");question.setDeliveryStatus("SAVED");
        question.setDeliveryDeadline(new Date(System.currentTimeMillis()+300000));
        when(questions.findByRequestId(1,10,"q")).thenReturn(question);
        when(questions.claimAnswerDelivery(eq(11L),any(),any())).thenReturn(1);
        delivery.deliver(question);commit();
        verifyNoInteractions(transport);
    }
    @ParameterizedTest @ValueSource(strings={"ack","timeout","recovery"})
    void systemFinalizationSettlesLeftoversWithoutPerQuestionRuntimeCancel(String path) {
        assertTrue(delivery.expireQuestion(question,cutoff));commit(); clearInvocations(browser,transport);
        var leftover=new AgentConversationElicitationDO();org.springframework.beans.BeanUtils.copyProperties(question,leftover);
        leftover.setRequestId("leftover");leftover.setStatus("PENDING");
        when(questions.listPendingByTurn(1,100)).thenReturn(List.of(leftover));
        when(questions.settleIfPending(1,10,"leftover","CANCELED",null)).thenReturn(1);
        if(path.equals("ack")) service.acknowledgeTurn(1L,9L,10L,100L,"SUCCESS",null,null,null,null,2);
        else if(path.equals("timeout")) service.handleQuestionExpiryTimeout(conv,100L,2);
        else {
            turn.setSourceContext("{\"questionExpiryStop\":{\"attempt\":2,\"executorId\":9,\"requestedAt\":1}}");
            when(turns.listStaleProcessingInboundByExecutor(eq(1L),eq(9L),any(),eq(100))).thenReturn(List.of(turn));
            service.recoverStaleTurnsForExecutor(1L,9L,Set.of(),true,new Date(),100);
        }
        verify(questions).settleIfPending(1,10,"leftover","CANCELED",null);
        verifyNoInteractions(browser,transport);commit();
        verify(browser).publishCommittedServerEvent(eq(1L),eq(10L),eq(100L),eq("acp_elicitation_resolved"),contains("leftover"));
        noAnswers();
    }
    @Test void expiryCommittedBetweenDeliveryAdmissionAndSendSuppressesWireAnswer() {
        question.setStatus("ANSWERED");question.setAnswerId("a");question.setDeliveryStatus("SAVED");
        question.setDeliveryDeadline(new Date(System.currentTimeMillis()+300000));
        when(questions.findByRequestId(1,10,"q")).thenReturn(question);
        when(questions.claimAnswerDelivery(eq(11L),any(),any())).thenReturn(1);
        delivery.deliver(question);verifyNoInteractions(transport);
        turn.setSourceContext("{\"questionExpiryStop\":{\"attempt\":2,\"executorId\":9,\"requestedAt\":1}}");
        commit();verifyNoInteractions(transport);
        verify(questions).closeAnswerDeliveriesForTurn(1,10,100);
    }

    @Test void realBrowserPublisherDeliversSystemSettlementAfterCommit() {
        var realtime=mock(com.aliyun.autowonder.websocket.ConversationRealtimePublisher.class);
        var publisher=new ConversationBrowserEventPublisher();publisher.setConversationRealtimePublisher(realtime);
        elicitations.setBrowserEventPublisher(publisher);
        assertTrue(delivery.expireQuestion(question,cutoff));verifyNoInteractions(realtime);
        commit();
        verify(realtime).publish(eq("conversation:10"),eq("CONVERSATION_TURN_EVENT"),any());
    }
    @Test void realBrowserPublisherDeliversTerminalExpiryAndLeftoverResolution() {
        assertTrue(delivery.expireQuestion(question,cutoff));commit();
        var realtime=mock(com.aliyun.autowonder.websocket.ConversationRealtimePublisher.class);
        var publisher=new ConversationBrowserEventPublisher();publisher.setConversationRealtimePublisher(realtime);
        elicitations.setBrowserEventPublisher(publisher);service.setBrowserEventPublisher(publisher);
        var late=new AgentConversationElicitationDO();org.springframework.beans.BeanUtils.copyProperties(question,late);
        late.setRequestId("leftover");late.setStatus("PENDING");
        when(questions.listPendingByTurn(1,100)).thenReturn(List.of(late));
        when(questions.settleIfPending(1,10,"leftover","CANCELED",null)).thenReturn(1);
        service.handleQuestionExpiryTimeout(conv,100L,2);verifyNoInteractions(realtime);commit();
        verify(realtime,times(2)).publish(eq("conversation:10"),eq("CONVERSATION_TURN_EVENT"),any());
    }

    @Test void lateQuestionRollbackNeverPublishesSettledOrRuntimeFrame() {
        assertTrue(delivery.expireQuestion(question,cutoff));commit();clearInvocations(browser,transport);
        when(questions.insertIfAbsent(any())).thenReturn(1);
        elicitations.onEvent(1,10,100,"acp_elicitation","{\"data\":{\"requestId\":\"late\"}}");
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(browser,transport);
    }
}
