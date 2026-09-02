package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;
import java.util.Date;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;

class ScheduledTaskRunCompensationTaskTest {
    @Test
    void unavailableCapabilityTouchesNeitherRedisNorScheduledDao() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ScheduledTaskRunOrchestrator orchestrator = mock(ScheduledTaskRunOrchestrator.class);
        ScheduledTaskRunRecoveryService recovery = mock(ScheduledTaskRunRecoveryService.class);
        RedisManager redis = mock(RedisManager.class);
        ScheduledTaskCapabilityGuard guard = mock(ScheduledTaskCapabilityGuard.class);

        new ScheduledTaskRunCompensationTask(runDao, orchestrator, recovery, redis,
                mock(ScheduledTaskRunService.class), guard).sweep();

        verify(guard).isAvailable();
        verifyNoInteractions(runDao, orchestrator, recovery, redis);
    }

    @Test
    void redrivesStaleStartingRunsUnderClusterLock() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ScheduledTaskRunOrchestrator orchestrator = mock(ScheduledTaskRunOrchestrator.class);
        ScheduledTaskRunRecoveryService recovery = mock(ScheduledTaskRunRecoveryService.class);
        RedisManager redis = mock(RedisManager.class);
        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO(); run.setId(77L); run.setWorkspaceId(1L); run.setStatus("STARTING");
        when(runDao.listStaleStarting(any(Date.class), eq(200))).thenReturn(List.of(run));
        when(runDao.listStaleQueued(any(Date.class), eq(200))).thenReturn(List.of());

        new ScheduledTaskRunCompensationTask(runDao, orchestrator, recovery, redis,
                new ScheduledTaskRunService(runDao), availableGuard()).sweep();

        verify(orchestrator).start(1L, 77L, 0L);
        verify(redis).releaseLock(eq("scheduled-task-run:compensation:lock"), anyString());
    }

    @Test
    void rereadsRunAfterRecoveryBeforeRequeueingWaitingExecutor() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ScheduledTaskRunOrchestrator orchestrator = mock(ScheduledTaskRunOrchestrator.class);
        ScheduledTaskRunRecoveryService recovery = mock(ScheduledTaskRunRecoveryService.class);
        RedisManager redis = mock(RedisManager.class);
        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        ScheduledTaskRunDO stale = run(77L, "WAITING_EXECUTOR", 2);
        ScheduledTaskRunDO refreshed = run(77L, "WAITING_EXECUTOR", 3);
        when(runDao.listStaleStarting(any(Date.class), eq(200))).thenReturn(List.of());
        when(runDao.listStaleQueued(any(Date.class), eq(200))).thenReturn(List.of(stale));
        when(runDao.findActiveByTask(1L, 12L)).thenReturn(List.of(stale));
        when(recovery.reconcile(stale)).thenReturn(ScheduledTaskRunRecoveryService.ResumePlan.none());
        when(runDao.findById(1L, 77L)).thenReturn(refreshed);
        when(runDao.updateStatus(1L, 77L, "WAITING_EXECUTOR", "QUEUED", 3, 0L)).thenReturn(1);

        new ScheduledTaskRunCompensationTask(runDao, orchestrator, recovery, redis,
                new ScheduledTaskRunService(runDao), availableGuard()).sweep();

        verify(runDao).updateStatus(1L, 77L, "WAITING_EXECUTOR", "QUEUED", 3, 0L);
        verify(orchestrator).start(1L, 77L, 0L);
    }

    private static ScheduledTaskRunDO run(long id, String status, int version) {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO(); run.setId(id); run.setWorkspaceId(1L);
        run.setScheduledTaskId(12L); run.setStatus(status); run.setVersion(version); return run;
    }

    private static ScheduledTaskCapabilityGuard availableGuard() {
        ScheduledTaskCapabilityGuard guard = mock(ScheduledTaskCapabilityGuard.class);
        when(guard.isAvailable()).thenReturn(true);
        return guard;
    }
}
