package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.PresenceManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceCleanupTaskTest {

    @Test
    void sendsOnlyToOnlineRuntimeThatSupportsAuthoritativeCleanup() {
        WorkspaceCleanupDao dao = mock(WorkspaceCleanupDao.class);
        WorkspaceCleanupTransport transport = mock(WorkspaceCleanupTransport.class);
        PresenceManager presence = mock(PresenceManager.class);
        RedisManager redis = mock(RedisManager.class);
        WorkspaceCleanupCandidate eligible = candidate(10001L, 20001L, 30001L, 7);
        WorkspaceCleanupCandidate offline = candidate(10001L, 20002L, 30002L, 8);
        WorkspaceCleanupCandidate legacy = candidate(10001L, 20003L, 30003L, 9);
        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(dao.listEligible(org.mockito.ArgumentMatchers.any(Date.class), anyInt()))
                .thenReturn(List.of(eligible, offline, legacy));
        when(presence.isExecutorOnline(30001L)).thenReturn(true);
        when(presence.supportsProtocolFeature(30001L, WorkspaceCleanupTask.PROTOCOL_FEATURE)).thenReturn(true);
        when(presence.isExecutorOnline(30002L)).thenReturn(false);
        when(presence.isExecutorOnline(30003L)).thenReturn(true);
        when(presence.supportsProtocolFeature(30003L, WorkspaceCleanupTask.PROTOCOL_FEATURE)).thenReturn(false);

        WorkspaceCleanupTask task = new WorkspaceCleanupTask(
                dao, transport, presence, redis, Duration.ofDays(7), 200);
        task.sweep();

        verify(transport).send(eligible);
        verify(transport, never()).send(offline);
        verify(transport, never()).send(legacy);
        ArgumentCaptor<Date> cutoff = ArgumentCaptor.forClass(Date.class);
        verify(dao).listEligible(cutoff.capture(), anyInt());
        assertTrue(cutoff.getValue().getTime() <= System.currentTimeMillis() - Duration.ofDays(7).toMillis());
    }

    @Test
    void suppressesOnlyTheSamePublishedGenerationDuringRetryWindow() {
        WorkspaceCleanupDao dao = mock(WorkspaceCleanupDao.class);
        WorkspaceCleanupTransport transport = mock(WorkspaceCleanupTransport.class);
        PresenceManager presence = mock(PresenceManager.class);
        RedisManager redis = mock(RedisManager.class);
        WorkspaceCleanupCandidate candidate = candidate(11L, 22L, 33L, 4);
        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(dao.listEligible(org.mockito.ArgumentMatchers.any(Date.class), anyInt()))
                .thenReturn(List.of(candidate));
        when(presence.isExecutorOnline(33L)).thenReturn(true);
        when(presence.supportsProtocolFeature(33L, WorkspaceCleanupTask.PROTOCOL_FEATURE)).thenReturn(true);
        when(redis.exists("workspace:cleanup:sent:33:22:4")).thenReturn(true);

        new WorkspaceCleanupTask(dao, transport, presence, redis, Duration.ofDays(7), 200).sweep();

        verify(transport, never()).send(candidate);
        verify(redis).releaseLock(anyString(), anyString());
    }

    @Test
    void transportFrameCarriesTheAuthoritativeIdentityAndPublicationTime() {
        WorkspaceCleanupCandidate candidate = candidate(11L, 22L, 33L, 4);
        assertEquals(new Date(123456789L), candidate.getPublishedAt());
        assertEquals(4, candidate.getWorkitemVersion());
    }

    private static WorkspaceCleanupCandidate candidate(long tenantId, long workitemId,
            long executorId, int version) {
        WorkspaceCleanupCandidate candidate = new WorkspaceCleanupCandidate();
        candidate.setTenantId(tenantId);
        candidate.setWorkitemId(workitemId);
        candidate.setExecutorId(executorId);
        candidate.setWorkitemVersion(version);
        candidate.setPublishedAt(new Date(123456789L));
        return candidate;
    }
}
