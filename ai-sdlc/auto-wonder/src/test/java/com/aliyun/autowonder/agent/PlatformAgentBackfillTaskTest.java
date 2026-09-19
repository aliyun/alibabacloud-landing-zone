package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.workspace.WorkspaceDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlatformAgentBackfillTaskTest {

    WorkspaceDao workspaceDao;
    PlatformAgentSeeder seeder;
    RedisManager redisManager;
    PlatformAgentBackfillTask task;

    @BeforeEach
    void setUp() {
        workspaceDao = mock(WorkspaceDao.class);
        seeder = mock(PlatformAgentSeeder.class);
        redisManager = mock(RedisManager.class);
        task = new PlatformAgentBackfillTask(workspaceDao, seeder, redisManager);
    }

    private WorkspaceDO workspace(long id, Long ownerId) {
        WorkspaceDO ws = new WorkspaceDO();
        ws.setId(id);
        ws.setOwnerId(ownerId);
        return ws;
    }

    @Test
    void skipsWhenLockNotAcquired() throws Exception {
        when(redisManager.tryAcquireLock(eq(PlatformAgentBackfillTask.LOCK_KEY), anyString(), anyLong()))
                .thenReturn(false);

        task.run(null);

        verifyNoInteractions(workspaceDao, seeder);
        verify(redisManager, never()).releaseLock(anyString(), anyString());
    }

    @Test
    void seedsEveryWorkspaceAndReleasesLock() throws Exception {
        when(redisManager.tryAcquireLock(eq(PlatformAgentBackfillTask.LOCK_KEY), anyString(), anyLong()))
                .thenReturn(true);
        when(workspaceDao.listAllPaged(isNull(), eq(0), anyInt()))
                .thenReturn(List.of(workspace(1L, 10L), workspace(2L, 20L)));

        task.run(null);

        verify(seeder).seed(1L, 10L);
        verify(seeder).seed(2L, 20L);
        ArgumentCaptor<String> ownerCap = ArgumentCaptor.forClass(String.class);
        verify(redisManager).tryAcquireLock(eq(PlatformAgentBackfillTask.LOCK_KEY), ownerCap.capture(), anyLong());
        verify(redisManager).releaseLock(PlatformAgentBackfillTask.LOCK_KEY, ownerCap.getValue());
    }

    @Test
    void singleWorkspaceFailureDoesNotAbortBatch() throws Exception {
        when(redisManager.tryAcquireLock(eq(PlatformAgentBackfillTask.LOCK_KEY), anyString(), anyLong()))
                .thenReturn(true);
        when(workspaceDao.listAllPaged(isNull(), eq(0), anyInt()))
                .thenReturn(List.of(workspace(1L, 10L), workspace(2L, 20L)));
        doThrow(new IllegalStateException("template missing")).when(seeder).seed(1L, 10L);

        task.run(null);

        verify(seeder).seed(2L, 20L);
        verify(redisManager).releaseLock(eq(PlatformAgentBackfillTask.LOCK_KEY), anyString());
    }

    @Test
    void nullOwnerFallsBackToZeroCreator() throws Exception {
        when(redisManager.tryAcquireLock(eq(PlatformAgentBackfillTask.LOCK_KEY), anyString(), anyLong()))
                .thenReturn(true);
        when(workspaceDao.listAllPaged(isNull(), eq(0), anyInt()))
                .thenReturn(List.of(workspace(3L, null)));

        task.run(null);

        verify(seeder).seed(3L, 0L);
    }

    @Test
    void redisOutageNeverFailsStartup() throws Exception {
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> task.run(null));
        verifyNoInteractions(seeder);
    }

    @Test
    void pagesThroughAllWorkspacesUntilEmptyPage() throws Exception {
        when(redisManager.tryAcquireLock(eq(PlatformAgentBackfillTask.LOCK_KEY), anyString(), anyLong()))
                .thenReturn(true);
        java.util.List<WorkspaceDO> fullPage = new java.util.ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            fullPage.add(workspace(i, (long) i));
        }
        when(workspaceDao.listAllPaged(isNull(), eq(0), anyInt())).thenReturn(fullPage);
        when(workspaceDao.listAllPaged(isNull(), eq(200), anyInt())).thenReturn(List.of());
        when(seeder.seed(anyLong(), anyLong())).thenReturn(true);

        task.run(null);

        verify(workspaceDao).listAllPaged(isNull(), eq(200), anyInt());
        verify(seeder, times(200)).seed(anyLong(), anyLong());
        verify(redisManager).releaseLock(eq(PlatformAgentBackfillTask.LOCK_KEY), anyString());
    }
}
