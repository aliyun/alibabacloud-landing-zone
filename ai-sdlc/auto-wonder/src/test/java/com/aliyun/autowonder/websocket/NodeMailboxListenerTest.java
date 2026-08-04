package com.aliyun.autowonder.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import javax.websocket.CloseReason;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeMailboxListenerTest {

    private SessionRegistry sessionRegistry;
    private PresenceManager presenceManager;
    private NodeMailboxListener listener;

    @BeforeEach
    void setUp() {
        sessionRegistry = mock(SessionRegistry.class);
        presenceManager = mock(PresenceManager.class);
        listener = new NodeMailboxListener(sessionRegistry, presenceManager);
    }

    @Test
    void deliversToLocalSessionWhenPresent() throws Exception {
        Session ws = mock(Session.class);
        RemoteEndpoint.Basic basic = mock(RemoteEndpoint.Basic.class);
        when(ws.getBasicRemote()).thenReturn(basic);
        when(ws.isOpen()).thenReturn(true);
        when(sessionRegistry.findByExecutorId(5L))
                .thenReturn(new ExecutorSession(5L, 10L, 100L, ws));

        String json = "{\"type\":\"TASK_DISPATCH\",\"dispatchId\":99,\"executorId\":5,\"downloadUrl\":\"https://oss/dl\"}";

        listener.onMessage("node:dispatch:broadcast", json);

        verify(basic).sendText(json);
    }

    @Test
    void ignoresWhenExecutorNotLocal() {
        when(sessionRegistry.findByExecutorId(5L)).thenReturn(null);

        String json = "{\"type\":\"TASK_DISPATCH\",\"dispatchId\":99,\"executorId\":5}";
        listener.onMessage("node:dispatch:broadcast", json);

        // no exception, no session interaction beyond the lookup
    }

    @Test
    void ignoresWhenSessionClosed() {
        Session ws = mock(Session.class);
        when(ws.isOpen()).thenReturn(false);
        when(sessionRegistry.findByExecutorId(5L))
                .thenReturn(new ExecutorSession(5L, 10L, 100L, ws));

        String json = "{\"type\":\"TASK_DISPATCH\",\"dispatchId\":99,\"executorId\":5}";
        listener.onMessage("node:dispatch:broadcast", json);

        verify(ws, never()).getBasicRemote();
    }

    @Test
    void malformedJsonDoesNotThrow() {
        listener.onMessage("node:dispatch:broadcast", "not json at all");
        // no exception
    }

    @Test
    void missingExecutorIdIgnored() {
        String json = "{\"type\":\"TASK_DISPATCH\",\"dispatchId\":99}";
        listener.onMessage("node:dispatch:broadcast", json);
        verify(sessionRegistry, never()).findByExecutorId(anyLong());
    }

    @Test
    void sessionCloseClosesLocalSession() throws Exception {
        Session ws = mock(Session.class);
        when(ws.isOpen()).thenReturn(true);
        when(sessionRegistry.findByExecutorId(5L))
                .thenReturn(new ExecutorSession(5L, 10L, 100L, ws));

        listener.onMessage("node:dispatch:broadcast",
                "{\"type\":\"SESSION_CLOSE\",\"executorId\":5}");

        verify(ws).close();
        verify(presenceManager).unregister(5L, 10L);
    }

    @Test
    void sessionCloseIgnoredWhenNotLocal() {
        when(sessionRegistry.findByExecutorId(5L)).thenReturn(null);

        listener.onMessage("node:dispatch:broadcast",
                "{\"type\":\"SESSION_CLOSE\",\"executorId\":5}");

        verify(presenceManager, never()).unregister(anyLong(), anyLong());
    }

    @Test
    void sessionReplacementClosesOnlyNonAuthoritativeLocalSession() throws Exception {
        Session ws = mock(Session.class);
        when(ws.getId()).thenReturn("session-old");
        when(ws.isOpen()).thenReturn(true);
        when(sessionRegistry.findByExecutorId(5L))
                .thenReturn(new ExecutorSession(5L, 10L, 100L, ws));
        when(presenceManager.currentSessionId(5L)).thenReturn("session-new");

        listener.onMessage("node:dispatch:broadcast",
                "{\"type\":\"SESSION_REPLACED\",\"executorId\":5}");

        ArgumentCaptor<CloseReason> reason = ArgumentCaptor.forClass(CloseReason.class);
        verify(ws).close(reason.capture());
        assertEquals(SessionRegistry.EXECUTOR_REPLACED_CLOSE_CODE,
                reason.getValue().getCloseCode().getCode());
        verify(presenceManager, never()).unregister(anyLong(), anyLong());
    }
}
