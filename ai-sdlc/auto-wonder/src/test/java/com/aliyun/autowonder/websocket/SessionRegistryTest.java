package com.aliyun.autowonder.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.websocket.Session;
import javax.websocket.CloseReason;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionRegistryTest {

    private SessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SessionRegistry();
    }

    @Test
    void registerAndFindByExecutorId() {
        Session ws = mock(Session.class);
        when(ws.getId()).thenReturn("sess-1");
        ExecutorSession es = new ExecutorSession(1L, 10L, 100L, ws);
        registry.register(es);

        ExecutorSession found = registry.findByExecutorId(1L);
        assertNotNull(found);
        assertEquals(10L, found.getAgentId());
        assertEquals(ws, found.getSession());
    }

    @Test
    void removeBySessionId() {
        Session ws = mock(Session.class);
        when(ws.getId()).thenReturn("sess-1");
        ExecutorSession es = new ExecutorSession(1L, 10L, 100L, ws);
        registry.register(es);

        registry.removeBySessionId("sess-1");
        assertNull(registry.findByExecutorId(1L));
    }

    @Test
    void duplicateExecutorReplacesOld() throws Exception {
        Session ws1 = mock(Session.class);
        when(ws1.getId()).thenReturn("sess-1");
        Session ws2 = mock(Session.class);
        when(ws2.getId()).thenReturn("sess-2");

        registry.register(new ExecutorSession(1L, 10L, 100L, ws1));
        registry.register(new ExecutorSession(1L, 10L, 100L, ws2));

        ExecutorSession found = registry.findByExecutorId(1L);
        assertEquals(ws2, found.getSession());
        assertTrue(found.consumeReplacementRecoveryPending());
        assertFalse(found.consumeReplacementRecoveryPending());
        assertFalse(registry.isCurrent(new ExecutorSession(1L, 10L, 100L, ws1)));
        assertTrue(registry.isCurrent(found));

        ArgumentCaptor<CloseReason> closeReason = ArgumentCaptor.forClass(CloseReason.class);
        verify(ws1).close(closeReason.capture());
        assertEquals(SessionRegistry.EXECUTOR_REPLACED_CLOSE_CODE,
                closeReason.getValue().getCloseCode().getCode());
        assertEquals(SessionRegistry.EXECUTOR_REPLACED_REASON,
                closeReason.getValue().getReasonPhrase());

        assertNull(registry.removeBySessionId("sess-1"));
        assertEquals(ws2, registry.findByExecutorId(1L).getSession());
        assertEquals(ws2, registry.removeBySessionId("sess-2").getSession());
    }

    @Test
    void findBySessionId() {
        Session ws = mock(Session.class);
        when(ws.getId()).thenReturn("sess-1");
        ExecutorSession es = new ExecutorSession(1L, 10L, 100L, ws);
        registry.register(es);

        ExecutorSession found = registry.findBySessionId("sess-1");
        assertNotNull(found);
        assertEquals(1L, found.getExecutorId());
    }
}
