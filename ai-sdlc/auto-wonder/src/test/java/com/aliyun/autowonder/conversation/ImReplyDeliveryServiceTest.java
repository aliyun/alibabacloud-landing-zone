package com.aliyun.autowonder.conversation;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImReplyDeliveryServiceTest {
    private final AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
    private final AgentConversationDao convDao = mock(AgentConversationDao.class);
    private final ConversationChannelSinkRegistry sinkRegistry =
            mock(ConversationChannelSinkRegistry.class);
    private final ConversationChannelSink sink = mock(ConversationChannelSink.class);
    private final ImReplyDeliveryService service =
            new ImReplyDeliveryService(turnDao, convDao, sinkRegistry);

    @Test
    void claimCommitsBeforeTheSendAndFinishCommitsAfterIt() {
        List<String> events = new ArrayList<>();
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doAnswer(inv -> {
            events.add("commit");
            return null;
        }).when(transactionManager).commit(any());
        ImReplyDeliveryService transactional =
                new ImReplyDeliveryService(turnDao, convDao, sinkRegistry);
        transactional.configureDeliveryTransactionManager(transactionManager);

        pendingTurn();
        when(sinkRegistry.find("DINGTALK")).thenReturn(sink);
        when(turnDao.claimPendingDelivery(eq(11L), any(Date.class))).thenAnswer(inv -> {
            events.add("claim");
            return 1;
        });
        doAnswer(inv -> {
            events.add("send");
            return null;
        }).when(sink).deliverReply(any(), anyString(), anyString());
        when(turnDao.finishDelivery(anyLong(), anyInt(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    events.add("finish");
                    return 1;
                });

        transactional.deliver(11L);

        assertEquals(List.of("claim", "commit", "send", "finish", "commit"), events,
                "the claim must be committed before the sink and the finish after it");
    }

    @Test
    void deliversClaimedReplyAndMarksItSent() {
        pendingTurn();
        when(sinkRegistry.find("DINGTALK")).thenReturn(sink);

        service.deliver(11L);

        ArgumentCaptor<AgentConversationDO> conv = ArgumentCaptor.forClass(AgentConversationDO.class);
        verify(sink).deliverReply(conv.capture(), eq("reply"), eq("ext-1"));
        assertEquals(2L, conv.getValue().getId());
        verify(turnDao).claimPendingDelivery(eq(11L), any(Date.class));
        verify(turnDao).finishDelivery(11L, 1, "SENT", null, null);
    }

    @Test
    void ignoresMissingAndAlreadyFinishedTurns() {
        service.deliver(null);
        when(turnDao.findDeliveryTurn(11L)).thenReturn(null);
        service.deliver(11L);
        AgentConversationTurnDO finished = turn("SENT");
        when(turnDao.findDeliveryTurn(12L)).thenReturn(finished);
        service.deliver(12L);

        verifyNoInteractions(convDao, sinkRegistry);
    }

    @Test
    void cancelsWhenConversationWasDeleted() {
        AgentConversationTurnDO turn = pendingTurn();
        when(convDao.findById(1L, 2L)).thenReturn(null);

        service.deliver(11L);

        verify(turnDao).cancelUndeliverableDelivery(11L,
                ImReplyDeliveryService.ERROR_MISSING_CONVERSATION);
        verify(turnDao, never()).claimPendingDelivery(anyLong(), any());
        assertNotNull(turn);
    }

    @Test
    void cancelsWhenChannelHasNoSink() {
        pendingTurn();
        when(sinkRegistry.find("DINGTALK")).thenReturn(null);

        service.deliver(11L);

        verify(turnDao).cancelUndeliverableDelivery(11L, ImReplyDeliveryService.ERROR_NO_SINK);
        verify(turnDao, never()).claimPendingDelivery(anyLong(), any());
    }

    @Test
    void cancelsWhenSourceInboundTurnIsMissing() {
        AgentConversationTurnDO turn = pendingTurn();
        turn.setSourceInboundTurnId(5L);
        claimedTurn();
        when(sinkRegistry.find("DINGTALK")).thenReturn(sink);
        when(turnDao.findByConversationTurn(1L, 2L, 5L)).thenReturn(null);

        service.deliver(11L);

        verify(turnDao).finishDelivery(11L, 1, "CANCELED",
                ImReplyDeliveryService.ERROR_MISSING_SOURCE, null);
        verifyNoInteractions(sink);
    }

    @Test
    void cancelsBlankReplyContent() {
        AgentConversationTurnDO turn = pendingTurn();
        turn.setContent("  ");
        AgentConversationTurnDO claimed = claimedTurn();
        claimed.setContent("  ");
        when(turnDao.findDeliveryTurn(11L)).thenReturn(turn, claimed);
        when(sinkRegistry.find("DINGTALK")).thenReturn(sink);

        service.deliver(11L);

        verify(turnDao).finishDelivery(11L, 1, "CANCELED",
                ImReplyDeliveryService.ERROR_EMPTY_REPLY, null);
        verifyNoInteractions(sink);
    }

    @Test
    void losingTheClaimRaceStopsWithoutFinishing() {
        pendingTurn();
        when(sinkRegistry.find("DINGTALK")).thenReturn(sink);
        when(turnDao.claimPendingDelivery(eq(11L), any(Date.class))).thenReturn(0);

        service.deliver(11L);

        verifyNoInteractions(sink);
        verify(turnDao, never()).finishDelivery(anyLong(), anyInt(), anyString(), any(), any());
    }

    @Test
    void sendingTurnWithoutDueTimeIsNotClaimed() {
        AgentConversationTurnDO turn = turn("SENDING");
        turn.setDeliveryAvailableAt(null);
        when(turnDao.findDeliveryTurn(11L)).thenReturn(turn);

        service.deliver(11L);

        verify(turnDao, never()).claimExpiredDelivery(anyLong(), any(), anyInt(), any());
    }

    @Test
    void expiredDingTalkLeaseIsMarkedUnknownInsteadOfResent() {
        AgentConversationTurnDO turn = turn("SENDING");
        turn.setDeliveryAvailableAt(new Date(System.currentTimeMillis() - 1000));
        when(turnDao.findDeliveryTurn(11L)).thenReturn(turn);
        when(convDao.findById(1L, 2L)).thenReturn(conversation("DINGTALK"));
        when(sinkRegistry.find("DINGTALK")).thenReturn(sink);
        when(turnDao.expireUnconfirmedDelivery(11L,
                ImReplyDeliveryService.ERROR_DINGTALK_LEASE_EXPIRED)).thenReturn(1);

        service.deliver(11L);

        verify(turnDao).expireUnconfirmedDelivery(11L,
                ImReplyDeliveryService.ERROR_DINGTALK_LEASE_EXPIRED);
        verify(turnDao, never()).claimExpiredDelivery(anyLong(), any(), anyInt(), any());
        verifyNoInteractions(sink);
    }

    @Test
    void expiredFeishuLeaseIsReclaimedWithinRetryWindow() {
        AgentConversationTurnDO turn = turn("SENDING");
        turn.setDeliveryAvailableAt(new Date(System.currentTimeMillis() - 1000));
        turn.setDeliveryAttempt(1);
        AgentConversationTurnDO claimed = claimedTurn();
        claimed.setDeliveryAttempt(2);
        when(turnDao.findDeliveryTurn(11L)).thenReturn(turn, claimed);
        when(convDao.findById(1L, 2L)).thenReturn(conversation("FEISHU"));
        when(sinkRegistry.find("FEISHU")).thenReturn(sink);
        when(turnDao.findByConversationTurn(1L, 2L, 5L)).thenReturn(sourceTurn());
        when(turnDao.finishDelivery(anyLong(), anyInt(), anyString(), any(), any()))
                .thenReturn(1);
        when(turnDao.claimExpiredDelivery(eq(11L), any(Date.class), eq(3), any(Date.class)))
                .thenReturn(1);

        service.deliver(11L);

        verify(turnDao).claimExpiredDelivery(eq(11L), any(Date.class), eq(3), any(Date.class));
        verify(turnDao).finishDelivery(11L, 2, "SENT", null, null);
    }

    @Test
    void exhaustedFeishuLeaseMarksUnknown() {
        AgentConversationTurnDO turn = turn("SENDING");
        turn.setDeliveryAvailableAt(new Date(System.currentTimeMillis() - 1000));
        when(turnDao.findDeliveryTurn(11L)).thenReturn(turn);
        when(convDao.findById(1L, 2L)).thenReturn(conversation("FEISHU"));
        when(sinkRegistry.find("FEISHU")).thenReturn(sink);
        when(turnDao.claimExpiredDelivery(anyLong(), any(), anyInt(), any())).thenReturn(0);
        when(turnDao.expireUnconfirmedDelivery(11L,
                ImReplyDeliveryService.ERROR_FEISHU_LEASE_EXPIRED)).thenReturn(1);

        service.deliver(11L);

        verify(turnDao).expireUnconfirmedDelivery(11L,
                ImReplyDeliveryService.ERROR_FEISHU_LEASE_EXPIRED);
    }

    @Test
    void changedTargetCancelsReply() {
        deliverFailing(new ImDeliveryTargetChangedException("target\nchanged"));

        verify(turnDao).finishDelivery(11L, 1, "CANCELED", "target changed", null);
    }

    @Test
    void definiteFailureFinishesFailed() {
        deliverFailing(new ImDeliveryFailedException("bad credentials"));

        verify(turnDao).finishDelivery(11L, 1, "FAILED", "bad credentials", null);
    }

    @Test
    void retryableFailureReturnsToPendingWithBackoff() {
        deliverFailing(new ImDeliveryRetryableException("rate limited"));

        ArgumentCaptor<Date> availableAt = ArgumentCaptor.forClass(Date.class);
        verify(turnDao).finishDelivery(eq(11L), eq(1), eq("PENDING"), eq("rate limited"),
                availableAt.capture());
        assertTrue(availableAt.getValue().getTime() >= System.currentTimeMillis() + 5_000);
    }

    @Test
    void retryableFailureAtChannelLimitIsFailed() {
        AgentConversationTurnDO turn = pendingTurn();
        when(turnDao.findDeliveryTurn(11L)).thenReturn(turn, claimedTurn(5));
        when(convDao.findById(1L, 2L)).thenReturn(conversation("DINGTALK"));
        when(sinkRegistry.find("DINGTALK")).thenReturn(sink);
        when(turnDao.claimPendingDelivery(eq(11L), any(Date.class))).thenReturn(1);
        doThrow(new ImDeliveryRetryableException("still failing")).when(sink)
                .deliverReply(any(), anyString(), anyString());

        service.deliver(11L);

        verify(turnDao).finishDelivery(11L, 5, "FAILED", "still failing", null);
    }

    @Test
    void unknownDingTalkOutcomeIsNeverRetried() {
        deliverFailing(new ImDeliveryUnknownException("timeout"));

        verify(turnDao).finishDelivery(11L, 1, "UNKNOWN", "timeout", null);
    }

    @Test
    void unknownFeishuOutcomeIsRetriedInsideDedupWindow() {
        Date created = new Date();
        AgentConversationTurnDO claimed = claimedTurn();
        claimed.setGmtCreate(created);
        AgentConversationTurnDO turn = pendingTurn();
        turn.setGmtCreate(created);
        when(turnDao.findDeliveryTurn(11L)).thenReturn(turn, claimed);
        when(convDao.findById(1L, 2L)).thenReturn(conversation("FEISHU"));
        when(sinkRegistry.find("FEISHU")).thenReturn(sink);
        when(turnDao.claimPendingDelivery(eq(11L), any(Date.class))).thenReturn(1);
        doThrow(new ImDeliveryUnknownException("lost response")).when(sink)
                .deliverReply(any(), anyString(), anyString());

        service.deliver(11L);

        verify(turnDao).finishDelivery(eq(11L), eq(1), eq("PENDING"), eq("lost response"),
                any(Date.class));
    }

    @Test
    void unknownFeishuOutcomeIsFinalAfterThirdAttempt() {
        feishuUnknownAtAttempt(3, new Date());

        verify(turnDao).finishDelivery(11L, 3, "UNKNOWN", "lost response", null);
    }

    @Test
    void unknownFeishuOutcomeOutsideDedupWindowIsFinal() {
        feishuUnknownAtAttempt(1,
                new Date(System.currentTimeMillis() - 2 * 60 * 60 * 1000L));

        verify(turnDao).finishDelivery(11L, 1, "UNKNOWN", "lost response", null);
    }

    @Test
    void unclassifiedFailureIsUnknownAndSanitizesError() {
        RuntimeException failure = new RuntimeException("line1\nline2\r\n" + "x".repeat(1200));
        deliverFailing(failure);

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(turnDao).finishDelivery(eq(11L), eq(1), eq("UNKNOWN"), error.capture(), isNull());
        assertEquals(1000, error.getValue().length());
        assertFalse(error.getValue().contains("\n"));
        assertFalse(error.getValue().contains("\r"));
    }

    @Test
    void failureWithoutMessageFallsBackToExceptionType() {
        deliverFailing(new RuntimeException());

        verify(turnDao).finishDelivery(11L, 1, "UNKNOWN", "RuntimeException", null);
    }

    @Test
    void scheduledScanIgnoresEmptyBatchesAndSurvivesSingleRowFailure() {
        when(turnDao.listDueDeliveryTurnIds(50)).thenReturn(null);
        service.deliverPending();
        when(turnDao.listDueDeliveryTurnIds(50)).thenReturn(List.of());
        service.deliverPending();
        verify(turnDao, never()).findDeliveryTurn(anyLong());

        when(turnDao.listDueDeliveryTurnIds(50)).thenReturn(List.of(11L));
        when(turnDao.findDeliveryTurn(11L)).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(service::deliverPending);
    }

    @Test
    void cancelIsLoggedWhenNoRowWasUpdated() {
        pendingTurn();
        when(sinkRegistry.find("DINGTALK")).thenReturn(null);
        when(turnDao.cancelUndeliverableDelivery(11L, ImReplyDeliveryService.ERROR_NO_SINK))
                .thenReturn(0);

        assertDoesNotThrow(() -> service.deliver(11L));
    }

    private void deliverFailing(RuntimeException failure) {
        pendingTurn();
        when(sinkRegistry.find("DINGTALK")).thenReturn(sink);
        try {
            doThrow(failure).when(sink).deliverReply(any(), anyString(), anyString());
        } catch (RuntimeException e) {
            throw new AssertionError(e);
        }
        service.deliver(11L);
    }

    private void feishuUnknownAtAttempt(int attempt, Date created) {
        AgentConversationTurnDO claimed = claimedTurn(attempt);
        claimed.setGmtCreate(created);
        AgentConversationTurnDO turn = pendingTurn();
        turn.setGmtCreate(created);
        when(turnDao.findDeliveryTurn(11L)).thenReturn(turn, claimed);
        when(convDao.findById(1L, 2L)).thenReturn(conversation("FEISHU"));
        when(sinkRegistry.find("FEISHU")).thenReturn(sink);
        when(turnDao.claimPendingDelivery(eq(11L), any(Date.class))).thenReturn(1);
        doThrow(new ImDeliveryUnknownException("lost response")).when(sink)
                .deliverReply(any(), anyString(), anyString());

        service.deliver(11L);
    }

    private AgentConversationTurnDO pendingTurn() {
        AgentConversationTurnDO turn = turn("PENDING");
        turn.setDeliveryStatus("PENDING");
        when(turnDao.findDeliveryTurn(11L)).thenReturn(turn, claimedTurn());
        when(convDao.findById(1L, 2L)).thenReturn(conversation("DINGTALK"));
        when(sinkRegistry.find("DINGTALK")).thenReturn(sink);
        when(turnDao.claimPendingDelivery(eq(11L), any(Date.class))).thenReturn(1);
        when(turnDao.findByConversationTurn(1L, 2L, 5L)).thenReturn(sourceTurn());
        when(turnDao.finishDelivery(anyLong(), anyInt(), anyString(), any(), any()))
                .thenReturn(1);
        return turn;
    }

    private AgentConversationTurnDO claimedTurn() {
        return claimedTurn(1);
    }

    private AgentConversationTurnDO claimedTurn(int attempt) {
        AgentConversationTurnDO turn = turn("PENDING");
        turn.setDeliveryAttempt(attempt);
        turn.setDeliveryStatus("SENDING");
        return turn;
    }

    private AgentConversationTurnDO turn(String deliveryStatus) {
        AgentConversationTurnDO turn = new AgentConversationTurnDO();
        turn.setId(11L);
        turn.setTenantId(1L);
        turn.setConversationId(2L);
        turn.setDeliveryStatus(deliveryStatus);
        turn.setDeliveryAttempt(0);
        turn.setContent("reply");
        turn.setSourceInboundTurnId(5L);
        return turn;
    }

    private AgentConversationTurnDO sourceTurn() {
        AgentConversationTurnDO source = new AgentConversationTurnDO();
        source.setId(5L);
        source.setTenantId(1L);
        source.setConversationId(2L);
        source.setExternalMsgId("ext-1");
        return source;
    }

    private AgentConversationDO conversation(String channel) {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(2L);
        conv.setTenantId(1L);
        conv.setChannel(channel);
        return conv;
    }
}
