package com.aliyun.autowonder.conversation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConversationAnswerDeliveryServiceTest {
    @Test void expiredQuestionNeverCreatesAnAnswerIntent() {
        var dao=mock(AgentConversationElicitationDao.class);
        var r=row(); r.setGmtCreate(new Date(0));
        when(dao.findByRequestIdForUpdate(1,10,"question")).thenReturn(r);
        var service=new ConversationAnswerDeliveryService(dao,mock(AgentConversationTurnDao.class),
                mock(AgentConversationDao.class),mock(ConversationTransport.class));
        service.expireQuestion(r,new Date());
        verify(dao,never()).saveAnswerIfPending(any());
    }

    @Test void savedAnswerSurvivesOfflineSendAndOnlySendsAfterCommit() {
        var dao = mock(AgentConversationElicitationDao.class);
        var turns = mock(AgentConversationTurnDao.class);
        var convs = mock(AgentConversationDao.class);
        var transport = mock(ConversationTransport.class);
        var row = row();
        var turn = new AgentConversationTurnDO(); turn.setStatus("PROCESSING"); turn.setDispatchAttempt(2);
        var conv = new AgentConversationDO(); conv.setExecutorId(9L); conv.setId(10L);
        when(turns.findByConversationTurnForUpdate(1L,10L,100L)).thenReturn(turn);
        when(convs.findById(1L,10L)).thenReturn(conv);
        when(dao.findByRequestId(1,10,"question")).thenReturn(row);
        when(dao.findByRequestIdForUpdate(1,10,"question")).thenReturn(row);
        when(dao.saveAnswerIfPending(any())).thenReturn(1);
        var service = new ConversationAnswerDeliveryService(dao,turns,convs,transport);
        service.submit(1,10,"question","accept","{\"choice\":1}");
        var captor = org.mockito.ArgumentCaptor.forClass(AgentConversationElicitationDO.class);
        verify(dao).saveAnswerIfPending(captor.capture());
        assertEquals("SAVED",captor.getValue().getDeliveryStatus());
        assertNotNull(captor.getValue().getAnswerId());
        assertEquals("{\"choice\":1}",captor.getValue().getAnswerJson());
        verifyNoInteractions(transport); // periodic sender runs only on committed rows
    }
    @Test void duplicateSameAnswerReturnsCurrentStateWithoutAnotherSave() {
        var dao=mock(AgentConversationElicitationDao.class);
        var row=row(); row.setStatus("ANSWERED"); row.setAnswerId("a"); row.setAnswerJson("{\"x\":1}"); row.setDeliveryStatus("RECEIVED");
        when(dao.findByRequestId(1,10,"question")).thenReturn(row);
        when(dao.findByRequestIdForUpdate(1,10,"question")).thenReturn(row);
        var service=new ConversationAnswerDeliveryService(dao,mock(AgentConversationTurnDao.class),mock(AgentConversationDao.class),mock(ConversationTransport.class));
        service.submit(1,10,"question","accept","{\"x\":1}");
        verify(dao,never()).saveAnswerIfPending(any());
        assertThrows(RuntimeException.class,()->service.submit(1,10,"question","accept","{\"x\":2}"));
    }
    @Test void receiptMustMatchOriginalExecutorAttemptAndAnswer() {
        var dao=mock(AgentConversationElicitationDao.class); var r=row(); r.setStatus("ANSWERED"); r.setAnswerId("a");
        when(dao.findByRequestId(1,10,"question")).thenReturn(r);
        var service=new ConversationAnswerDeliveryService(dao,mock(AgentConversationTurnDao.class),mock(AgentConversationDao.class),mock(ConversationTransport.class));
        service.receive(1,99,10,100,2,"question","a","WRITTEN");
        service.receive(1,9,10,100,3,"question","a","WRITTEN");
        service.receive(1,9,10,100,2,"question","wrong","WRITTEN");
        verify(dao,never()).advanceAnswerDelivery(anyLong(),anyString(),anyString());
        service.receive(1,9,10,100,2,"question","a","WRITTEN");
        verify(dao).advanceAnswerDelivery(11,"a","WRITTEN");
    }
    @Test void expiredDeliveryBecomesUnknownWithoutSendingAgain() {
        var dao=mock(AgentConversationElicitationDao.class); var r=row(); r.setStatus("ANSWERED"); r.setAnswerId("a");r.setDeliveryStatus("SAVED");r.setDeliveryDeadline(new Date(0));
        when(dao.findByRequestId(1,10,"question")).thenReturn(r);
        when(dao.claimAnswerDelivery(eq(11L),any(),any())).thenReturn(1);
        var transport=mock(ConversationTransport.class);
        var service=new ConversationAnswerDeliveryService(dao,mock(AgentConversationTurnDao.class),mock(AgentConversationDao.class),transport);
        service.deliver(r);
        verify(dao).advanceAnswerDelivery(11,"a","UNKNOWN");
        verifyNoInteractions(transport);
    }

    @Test void deliveryWaitsForTransactionCommitAndKeepsOriginalIdentity() {
        var dao=mock(AgentConversationElicitationDao.class); var r=row(); r.setStatus("ANSWERED"); r.setAnswerId("a");r.setStatus("ANSWERED");r.setDeliveryStatus("SAVED");r.setDeliveryDeadline(new Date(System.currentTimeMillis()+300000));
        when(dao.findByRequestId(1,10,"question")).thenReturn(r);
        when(dao.claimAnswerDelivery(eq(11L),any(),any())).thenReturn(1);
        var turns=mock(AgentConversationTurnDao.class); var t=new AgentConversationTurnDO();t.setStatus("PROCESSING");t.setDispatchAttempt(2);
        when(turns.findByConversationTurnForUpdate(1L,10L,100L)).thenReturn(t);
        var convs=mock(AgentConversationDao.class); var c=new AgentConversationDO();c.setExecutorId(9L);
        when(convs.findById(1L,10L)).thenReturn(c);
        var transport=mock(ConversationTransport.class);
        var service=new ConversationAnswerDeliveryService(dao,turns,convs,transport);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.deliver(r); verifyNoInteractions(transport);
            var callbacks=org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            callbacks.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verify(transport).sendReliableElicitationReply(eq(c),eq(r),longThat(n->n>0 && n<=300000));
        } finally {org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();}
    }

    @Test void concurrentIdenticalSubmitUsesCurrentLockingRead() {
        var dao=mock(AgentConversationElicitationDao.class);var pending=row();var saved=row();
        saved.setStatus("ANSWERED");saved.setAnswerId("existing");saved.setAnswerJson("{\"x\":1}");
        when(dao.findByRequestId(1,10,"question")).thenReturn(pending);
        when(dao.findByRequestIdForUpdate(1,10,"question")).thenReturn(saved);
        var service=new ConversationAnswerDeliveryService(dao,mock(AgentConversationTurnDao.class),mock(AgentConversationDao.class),mock(ConversationTransport.class));
        service.submit(1,10,"question","accept","{\"x\":1}");
        verify(dao,never()).saveAnswerIfPending(any());
    }
    @Test void unknownReceiptTerminationRunsAfterCommitInSeparateService() {
        var dao=mock(AgentConversationElicitationDao.class);var r=row();r.setStatus("ANSWERED"); r.setAnswerId("a");r.setDeliveryStatus("UNKNOWN");
        when(dao.findByRequestId(1,10,"question")).thenReturn(r);
        when(dao.claimAnswerDelivery(eq(11L),any(),any())).thenReturn(1);
        var service=new ConversationAnswerDeliveryService(dao,mock(AgentConversationTurnDao.class),mock(AgentConversationDao.class),mock(ConversationTransport.class));
        var termination=mock(ConversationAnswerTerminationService.class);service.setTerminationService(termination);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.deliver(r);verifyNoInteractions(termination);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verify(termination).terminate(r);
        } finally {org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();}
    }

    @Test void terminalTurnCanOnlyQueryReceiptWithoutResumingProvider() {
        var dao=mock(AgentConversationElicitationDao.class);var r=row();r.setStatus("ANSWERED"); r.setAnswerId("a");r.setDeliveryStatus("SAVED");r.setDeliveryDeadline(new Date(System.currentTimeMillis()+300000));
        when(dao.findByRequestId(1,10,"question")).thenReturn(r);
        when(dao.claimAnswerDelivery(eq(11L),any(),any())).thenReturn(1);
        var turns=mock(AgentConversationTurnDao.class);var t=new AgentConversationTurnDO();t.setStatus("FAILED");t.setDispatchAttempt(2);
        when(turns.findByConversationTurnForUpdate(1L,10L,100L)).thenReturn(t);
        var convs=mock(AgentConversationDao.class);var c=new AgentConversationDO();c.setExecutorId(9L);
        when(convs.findById(1L,10L)).thenReturn(c);
        var transport=mock(ConversationTransport.class);
        new ConversationAnswerDeliveryService(dao,turns,convs,transport).deliver(r);
        verify(transport).sendReliableElicitationReply(eq(c),argThat(AgentConversationElicitationDO::isReceiptOnly),anyLong());
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS,SAVED", "SUCCESS,RECEIVED", "FAILED,SAVED", "CANCELED,RECEIVED"})
    void terminalReceiptQueryKeepsOriginalExecutorAfterNextTurnRebind(String status, String delivery) {
        var f = deliveryFixture(status, 99L);
        f.row.setDeliveryStatus(delivery);
        Date deadline = f.row.getDeliveryDeadline();
        f.service.deliver(f.row);
        verify(f.transport).sendReliableElicitationReply(eq(f.conv), argThat(r ->
                r.isReceiptOnly() && r.getExecutorId() == 9L && r.getDispatchAttempt() == 2
                        && "a".equals(r.getAnswerId()) && r.getTurnId() == 100L),
                longThat(ttl -> ttl > 0 && ttl <= 300000));
        verify(f.dao, never()).advanceAnswerDelivery(anyLong(), anyString(), anyString());
        verify(f.convs, never()).updateExecutor(anyLong(), anyLong(), anyLong());
        assertEquals(99L, f.conv.getExecutorId());
        assertEquals(deadline, f.row.getDeliveryDeadline());
    }

    @Test void activeDeliveryStillRequiresCurrentOwnerAndUsesOrdinaryReply() {
        var active = deliveryFixture("PROCESSING", 9L);
        active.service.deliver(active.row);
        verify(active.transport).sendReliableElicitationReply(eq(active.conv),
                argThat(r -> !r.isReceiptOnly()), anyLong());
        var rebound = deliveryFixture("PROCESSING", 99L);
        rebound.service.deliver(rebound.row);
        verify(rebound.dao).advanceAnswerDelivery(11L, "a", "UNKNOWN");
        verifyNoInteractions(rebound.transport);
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"QUEUED", "UNKNOWN", "success", ""})
    void onlyCanonicalTerminalTurnsCanQueryReceipts(String status) {
        var f = deliveryFixture(status, 9L);
        f.service.deliver(f.row);
        verify(f.dao).advanceAnswerDelivery(11L, "a", "UNKNOWN");
        verifyNoInteractions(f.transport);
    }

    @Test void changedAttemptCannotUseTerminalException() {
        var f = deliveryFixture("SUCCESS", 99L);
        f.turn.setDispatchAttempt(3);
        f.service.deliver(f.row);
        verify(f.dao).advanceAnswerDelivery(11L, "a", "UNKNOWN");
        verifyNoInteractions(f.transport);
    }

    @ParameterizedTest @NullSource @ValueSource(longs = {0L, -1L})
    void terminalExceptionRequiresLegalOriginalExecutor(Long executor) {
        var f = deliveryFixture("SUCCESS", 99L);
        f.row.setExecutorId(executor);
        f.service.deliver(f.row);
        verify(f.dao).advanceAnswerDelivery(11L, "a", "UNKNOWN");
        verifyNoInteractions(f.transport);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void terminalQuerySendsOnlyAfterCommitAndNeverAfterRollback(boolean commit) {
        var f = deliveryFixture("SUCCESS", 99L);
        var manager = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition definition) { }
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { }
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
        };
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx -> {
            f.service.deliver(f.row);
            verifyNoInteractions(f.transport);
            if (!commit) tx.setRollbackOnly();
        });
        if (commit) verify(f.transport).sendReliableElicitationReply(eq(f.conv),
                argThat(AgentConversationElicitationDO::isReceiptOnly), anyLong());
        else verifyNoInteractions(f.transport);
    }

    @Test void offlineOriginalExecutorRetriesWithoutChangingDeadlineOrTerminating() {
        var f = deliveryFixture("SUCCESS", 99L);
        var termination = mock(ConversationAnswerTerminationService.class);
        f.service.setTerminationService(termination);
        Date deadline = f.row.getDeliveryDeadline();
        doThrow(new IllegalStateException("synthetic offline")).doNothing().when(f.transport)
                .sendReliableElicitationReply(any(), any(), anyLong());
        f.service.deliver(f.row);
        f.service.deliver(f.row);
        verify(f.transport, times(2)).sendReliableElicitationReply(eq(f.conv),
                argThat(AgentConversationElicitationDO::isReceiptOnly), anyLong());
        assertEquals(deadline, f.row.getDeliveryDeadline());
        verify(f.dao, never()).advanceAnswerDelivery(anyLong(), anyString(), anyString());
        verifyNoInteractions(termination);
        f.row.setDeliveryDeadline(new Date(0));
        f.service.deliver(f.row);
        verify(f.dao).advanceAnswerDelivery(11L, "a", "UNKNOWN");
        verify(termination).terminate(f.row);
        verifyNoMoreInteractions(f.transport);
    }

    @Test void historicalSyntheticExpiryCannotDeliverOrBecomeHumanReceipt() {
        var f=deliveryFixture("PROCESSING",9L); f.row.setStatus("EXPIRED");
        f.service.deliver(f.row);
        f.service.receive(1,9,10,100,2,"question","a","WRITTEN");
        verifyNoInteractions(f.transport);
        verify(f.dao,never()).advanceAnswerDelivery(anyLong(),anyString(),anyString());
    }

    @Test void freshAdmissionReleasesDatabaseTransactionBeforeTransportEvenIfStopThenWins() {
        var f=deliveryFixture("PROCESSING",9L);
        var active=new java.util.concurrent.atomic.AtomicBoolean();
        var manager=new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            protected Object doGetTransaction(){return new Object();}
            protected void doBegin(Object tx,org.springframework.transaction.TransactionDefinition definition){active.set(true);}
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){
                active.set(false);
                f.turn.setSourceContext("{\"questionExpiryStop\":{\"attempt\":2,\"executorId\":9,\"requestedAt\":1}}");
            }
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status){active.set(false);}
        };
        f.service.configureSendTransaction(manager);
        doAnswer(invocation->{assertFalse(active.get(),"socket I/O must not retain the admission row lock");return null;})
                .when(f.transport).sendReliableElicitationReply(any(),any(),anyLong());
        f.service.deliver(f.row);
        verify(f.transport).sendReliableElicitationReply(any(),argThat(row->"ANSWERED".equals(row.getStatus()) && !row.isReceiptOnly()),anyLong());
    }

    private DeliveryFixture deliveryFixture(String status, Long currentExecutor) {
        var dao = mock(AgentConversationElicitationDao.class);
        var turns = mock(AgentConversationTurnDao.class);
        var convs = mock(AgentConversationDao.class);
        var transport = mock(ConversationTransport.class);
        var r = row(); r.setStatus("ANSWERED"); r.setAnswerId("a"); r.setStatus("ANSWERED"); r.setDeliveryStatus("SAVED");
        r.setDeliveryDeadline(new Date(System.currentTimeMillis() + 300000));
        var turn = new AgentConversationTurnDO(); turn.setStatus(status); turn.setDispatchAttempt(2);
        turn.setTenantId(1L); turn.setConversationId(10L); turn.setId(100L); turn.setDirection("IN");
        var conv = new AgentConversationDO(); conv.setTenantId(1L); conv.setId(10L); conv.setExecutorId(currentExecutor);
        when(turns.findByConversationTurnForUpdate(1L, 10L, 100L)).thenReturn(turn);
        when(convs.findById(1L, 10L)).thenReturn(conv);
        when(dao.findByRequestId(1, 10, "question")).thenReturn(r);
        when(dao.claimAnswerDelivery(eq(11L), any(), any())).thenReturn(1);
        return new DeliveryFixture(dao, convs, transport, r, turn, conv,
                new ConversationAnswerDeliveryService(dao, turns, convs, transport));
    }

    private record DeliveryFixture(AgentConversationElicitationDao dao, AgentConversationDao convs,
            ConversationTransport transport, AgentConversationElicitationDO row, AgentConversationTurnDO turn,
            AgentConversationDO conv, ConversationAnswerDeliveryService service) { }

    private AgentConversationElicitationDO row() {
        var r=new AgentConversationElicitationDO(); r.setId(11L);r.setTenantId(1L);r.setConversationId(10L);r.setTurnId(100L);r.setRequestId("question");r.setExecutorId(9L);r.setDispatchAttempt(2);r.setAnswerProtocolVersion(1);r.setStatus("PENDING");r.setGmtCreate(new Date());return r;
    }
}
