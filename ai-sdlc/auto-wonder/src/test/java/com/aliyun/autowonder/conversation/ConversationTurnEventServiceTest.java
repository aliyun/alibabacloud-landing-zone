package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.websocket.ConversationRealtimePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationTurnEventServiceTest {

    private final AgentConversationTurnEventDao eventDao = mock(AgentConversationTurnEventDao.class);
    private final AgentConversationDao convDao = mock(AgentConversationDao.class);
    private final AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
    private final ConversationRealtimePublisher publisher = mock(ConversationRealtimePublisher.class);

    private ConversationTurnEventService service;

    @BeforeEach
    void setUp() {
        service = new ConversationTurnEventService(eventDao, convDao, turnDao);
        service.setConversationRealtimePublisher(publisher);
    }

    @Test
    void rejectsEventWhenExecutorMismatch() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setExecutorId(99L);
        when(convDao.findById(1L, 10L)).thenReturn(conv);

        service.persistEvent(1L, 50L, 10L, 20L, 1, 1L, 0, 1, "text", "{}");

        verify(eventDao, never()).insertChunkIfAbsent(any());
    }

    @Test
    void rejectsEventWhenConversationNotFound() {
        when(convDao.findById(1L, 10L)).thenReturn(null);

        service.persistEvent(1L, 50L, 10L, 20L, 1, 1L, 0, 1, "text", "{}");

        verify(eventDao, never()).insertChunkIfAbsent(any());
    }

    @Test
    void rejectsEventWhenNoActiveTurn() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setExecutorId(50L);
        when(convDao.findById(1L, 10L)).thenReturn(conv);
        when(turnDao.findProcessingInbound(1L, 10L)).thenReturn(null);

        service.persistEvent(1L, 50L, 10L, 20L, 1, 1L, 0, 1, "text", "{}");

        verify(eventDao, never()).insertChunkIfAbsent(any());
    }

    @Test
    void rejectsEventWhenTurnIdMismatch() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setExecutorId(50L);
        when(convDao.findById(1L, 10L)).thenReturn(conv);
        AgentConversationTurnDO turn = new AgentConversationTurnDO();
        turn.setId(99L);
        when(turnDao.findProcessingInbound(1L, 10L)).thenReturn(turn);

        service.persistEvent(1L, 50L, 10L, 20L, 1, 1L, 0, 1, "text", "{}");

        verify(eventDao, never()).insertChunkIfAbsent(any());
    }

    @Test
    void persistsSingleChunkEventAndPublishes() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setExecutorId(50L);
        when(convDao.findById(1L, 10L)).thenReturn(conv);
        AgentConversationTurnDO turn = new AgentConversationTurnDO();
        turn.setId(20L);
        when(turnDao.findProcessingInbound(1L, 10L)).thenReturn(turn);

        String payload = "{\"content\":\"hello\"}";
        service.persistEvent(1L, 50L, 10L, 20L, 1, 1L, 0, 1, "text", payload);

        verify(eventDao).insertChunkIfAbsent(any());
        verify(publisher).publish(eq("conversation:10"), eq("CONVERSATION_TURN_EVENT"), any());
    }

    @Test
    void publishesOnlyAfterAllChunksPresent() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setExecutorId(50L);
        when(convDao.findById(1L, 10L)).thenReturn(conv);
        AgentConversationTurnDO turn = new AgentConversationTurnDO();
        turn.setId(20L);
        when(turnDao.findProcessingInbound(1L, 10L)).thenReturn(turn);

        AgentConversationTurnEventDO chunk0 = new AgentConversationTurnEventDO();
        chunk0.setPayloadFragment("{\"con");
        AgentConversationTurnEventDO chunk1 = new AgentConversationTurnEventDO();
        chunk1.setPayloadFragment("tent\":\"hi\"}");

        when(eventDao.listLogicalEventChunks(1L, 20L, 1, 1L))
                .thenReturn(List.of(chunk0))
                .thenReturn(List.of(chunk0, chunk1));

        service.persistEvent(1L, 50L, 10L, 20L, 1, 1L, 0, 2, "text", "{\"con");
        verify(publisher, never()).publish(anyString(), anyString(), any());

        service.persistEvent(1L, 50L, 10L, 20L, 1, 1L, 1, 2, "text", "tent\":\"hi\"}");
        verify(publisher).publish(eq("conversation:10"), eq("CONVERSATION_TURN_EVENT"), any());
    }

    @Test
    void updatesCliSessionRefOnStatusEvent() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setExecutorId(50L);
        when(convDao.findById(1L, 10L)).thenReturn(conv);
        AgentConversationTurnDO turn = new AgentConversationTurnDO();
        turn.setId(20L);
        when(turnDao.findProcessingInbound(1L, 10L)).thenReturn(turn);

        String payload = "{\"sessionId\":\"sess-abc\"}";
        service.persistEvent(1L, 50L, 10L, 20L, 1, 1L, 0, 1, "status", payload);

        verify(convDao).updateCliSessionRef(1L, 10L, "sess-abc");
    }

    @Test
    void doesNotUpdateCliSessionRefWhenSessionIdBlank() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setExecutorId(50L);
        when(convDao.findById(1L, 10L)).thenReturn(conv);
        AgentConversationTurnDO turn = new AgentConversationTurnDO();
        turn.setId(20L);
        when(turnDao.findProcessingInbound(1L, 10L)).thenReturn(turn);

        String payload = "{\"status\":\"running\"}";
        service.persistEvent(1L, 50L, 10L, 20L, 1, 1L, 0, 1, "status", payload);

        verify(convDao, never()).updateCliSessionRef(anyLong(), anyLong(), anyString());
    }

    @Test
    void listEventsAfterDelegates() {
        when(eventDao.listCompletedAfter(1L, 10L, 5L, 200)).thenReturn(Collections.emptyList());
        List<AgentConversationTurnEventDO> result = service.listEventsAfter(1L, 10L, 5L, 200);
        assertTrue(result.isEmpty());
        verify(eventDao).listCompletedAfter(1L, 10L, 5L, 200);
    }
}
