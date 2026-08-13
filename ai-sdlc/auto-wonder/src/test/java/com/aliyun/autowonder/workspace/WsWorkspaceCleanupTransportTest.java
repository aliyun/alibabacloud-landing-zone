package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.ExecutorSession;
import com.aliyun.autowonder.websocket.SessionRegistry;
import com.aliyun.autowonder.websocket.WsDispatchTransport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WsWorkspaceCleanupTransportTest {

    @Test
    void sendsAuthoritativeCleanupIdentityToLocalExecutor() throws Exception {
        SessionRegistry sessions = mock(SessionRegistry.class);
        RedisManager redis = mock(RedisManager.class);
        Session session = mock(Session.class);
        RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getBasicRemote()).thenReturn(remote);
        when(sessions.findByExecutorId(33L))
                .thenReturn(new ExecutorSession(33L, 44L, 11L, session));

        new WsWorkspaceCleanupTransport(sessions, redis).send(candidate());

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(remote).sendText(payload.capture());
        assertTrue(payload.getValue().contains("\"type\":\"WORKITEM_WORKSPACE_CLEANUP\""));
        assertTrue(payload.getValue().contains("\"executorId\":33"));
        assertTrue(payload.getValue().contains("\"tenantId\":11"));
        assertTrue(payload.getValue().contains("\"workitemId\":22"));
        assertTrue(payload.getValue().contains("\"workitemVersion\":4"));
        assertTrue(payload.getValue().contains("\"publishedAt\":123456789"));
        verify(redis, never()).publish(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void broadcastsWhenExecutorLivesOnAnotherServerNode() {
        SessionRegistry sessions = mock(SessionRegistry.class);
        RedisManager redis = mock(RedisManager.class);
        when(sessions.findByExecutorId(33L)).thenReturn(null);

        new WsWorkspaceCleanupTransport(sessions, redis).send(candidate());

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis).publish(org.mockito.ArgumentMatchers.eq(WsDispatchTransport.BROADCAST_CHANNEL),
                payload.capture());
        assertTrue(payload.getValue().contains("\"workitemId\":22"));
    }

    private static WorkspaceCleanupCandidate candidate() {
        WorkspaceCleanupCandidate candidate = new WorkspaceCleanupCandidate();
        candidate.setTenantId(11L);
        candidate.setWorkitemId(22L);
        candidate.setExecutorId(33L);
        candidate.setWorkitemVersion(4);
        candidate.setPublishedAt(new Date(123456789L));
        return candidate;
    }
}
