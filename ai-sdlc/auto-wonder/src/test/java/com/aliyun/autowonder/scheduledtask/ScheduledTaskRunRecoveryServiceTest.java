package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import com.aliyun.autowonder.dispatch.DispatchCheckpointService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduledTaskRunRecoveryServiceTest {
    @Test
    void waitsForSourceExecutorThenDegradesWithEvidence() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ExecutorSelector selector = mock(ExecutorSelector.class);
        DispatchService dispatchService = mock(DispatchService.class);
        Instant now = Instant.parse("2026-08-11T00:31:00Z");
        ScheduledTaskRunRecoveryService recovery = new ScheduledTaskRunRecoveryService(runDao, dispatchDao,
                selector, dispatchService, Clock.fixed(now, ZoneOffset.UTC));
        attachResumableCheckpoint(recovery);
        ScheduledTaskRunDO run = continuousRun(77L, now.minusSeconds(31 * 60));
        ScheduledTaskRunDO sourceRun = terminalRun(66L);
        DispatchDO source = sourceDispatch(88L);
        when(runDao.listByTask(1L, 12L, 200, 0)).thenReturn(List.of(run, sourceRun));
        when(dispatchDao.listLatestBySourceAndAgent(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(),
                66L, 20L, 20)).thenReturn(List.of(source));
        when(selector.isAvailable(88L)).thenReturn(false);
        when(dispatchService.fencePendingContinuousResume(1L, 77L)).thenReturn(true);

        ScheduledTaskRunRecoveryService.ResumePlan plan = recovery.reconcile(run);

        assertEquals(ScheduledTaskRunRecoveryService.State.DEGRADED, plan.state());
        verify(dispatchService).fencePendingContinuousResume(1L, 77L);
        verify(dispatchService).degradeResume(77L, 900L,
                ScheduledTaskRunRecoveryService.SOURCE_EXECUTOR_TIMEOUT, 1L);
        verify(runDao).markDegraded(1L, 77L,
                ScheduledTaskRunRecoveryService.SOURCE_EXECUTOR_TIMEOUT, 66L, 9, 0L);
    }

    @Test
    void doesNotDegradeWhileNativeDispatchFenceLosesRace() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class); DispatchDao dispatchDao = mock(DispatchDao.class);
        ExecutorSelector selector = mock(ExecutorSelector.class); DispatchService dispatchService = mock(DispatchService.class);
        Instant now = Instant.parse("2026-08-11T00:31:00Z");
        ScheduledTaskRunRecoveryService recovery = new ScheduledTaskRunRecoveryService(runDao, dispatchDao, selector,
                dispatchService, Clock.fixed(now, ZoneOffset.UTC));
        attachResumableCheckpoint(recovery);
        ScheduledTaskRunDO run = continuousRun(77L, now.minusSeconds(31 * 60)); ScheduledTaskRunDO sourceRun = terminalRun(66L);
        DispatchDO source = sourceDispatch(88L);
        when(runDao.listByTask(1L, 12L, 200, 0)).thenReturn(List.of(run, sourceRun));
        when(dispatchDao.listLatestBySourceAndAgent(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 66L, 20L, 20)).thenReturn(List.of(source));
        when(selector.isAvailable(88L)).thenReturn(false);
        when(dispatchService.fencePendingContinuousResume(1L, 77L)).thenReturn(false);

        assertEquals(ScheduledTaskRunRecoveryService.State.WAIT, recovery.reconcile(run).state());
        verify(dispatchService, never()).degradeResume(anyLong(), anyLong(), anyString(), anyLong());
        verify(runDao, never()).markDegraded(anyLong(), anyLong(), anyString(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void keepsNativeSessionOnlyWhenSourceExecutorIsAvailable() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ExecutorSelector selector = mock(ExecutorSelector.class);
        ScheduledTaskRunRecoveryService recovery = new ScheduledTaskRunRecoveryService(runDao, dispatchDao,
                selector, mock(DispatchService.class), Clock.systemUTC());
        attachResumableCheckpoint(recovery);
        ScheduledTaskRunDO run = continuousRun(77L, Instant.now());
        ScheduledTaskRunDO sourceRun = terminalRun(66L);
        DispatchDO source = sourceDispatch(88L);
        when(runDao.listByTask(1L, 12L, 200, 0)).thenReturn(List.of(run, sourceRun));
        when(dispatchDao.listLatestBySourceAndAgent(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(),
                66L, 20L, 20)).thenReturn(List.of(source));
        when(selector.isAvailable(88L)).thenReturn(true);

        ScheduledTaskRunRecoveryService.ResumePlan plan = recovery.reconcile(run);

        assertEquals(ScheduledTaskRunRecoveryService.State.AFFINE, plan.state());
        verify(runDao).markResumeSource(1L, 77L, 66L, 9, 0L);
    }

    @Test
    void repeatedDegradationDoesNotRewriteOrRenotifyRun() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ExecutorSelector selector = mock(ExecutorSelector.class);
        DispatchService dispatchService = mock(DispatchService.class);
        Instant now = Instant.parse("2026-08-11T00:31:00Z");
        ScheduledTaskRunRecoveryService recovery = new ScheduledTaskRunRecoveryService(runDao, dispatchDao,
                selector, dispatchService, Clock.fixed(now, ZoneOffset.UTC));
        attachResumableCheckpoint(recovery);
        ScheduledTaskRunDO run = continuousRun(77L, now.minusSeconds(31 * 60));
        run.setDegradedResume(1); run.setDegradedReason(ScheduledTaskRunRecoveryService.SOURCE_EXECUTOR_TIMEOUT);
        run.setResumeFromRunId(66L);
        ScheduledTaskRunDO sourceRun = terminalRun(66L); DispatchDO source = sourceDispatch(88L);
        when(runDao.listByTask(1L, 12L, 200, 0)).thenReturn(List.of(run, sourceRun));
        when(dispatchDao.listLatestBySourceAndAgent(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 66L, 20L, 20)).thenReturn(List.of(source));
        when(selector.isAvailable(88L)).thenReturn(false);

        recovery.reconcile(run);

        verifyNoInteractions(dispatchService);
        verify(runDao, never()).markDegraded(anyLong(), anyLong(), anyString(), anyLong(), anyInt(), anyLong());
    }

    @Test void actualTimeoutDegradationUsesRecipientIntentForPrimaryAndDirectFallbackWithoutChangingRawWrites() {
        for (boolean primary : List.of(true,false)) {
            var runs=mock(ScheduledTaskRunDao.class);var dispatches=mock(DispatchDao.class);
            var selector=mock(ExecutorSelector.class);var service=mock(DispatchService.class);
            Instant now=Instant.parse("2026-08-11T00:31:00Z");
            var recovery=new ScheduledTaskRunRecoveryService(runs,dispatches,selector,service,Clock.fixed(now,ZoneOffset.UTC));
            attachResumableCheckpoint(recovery);
            var run=continuousRun(77L,now.minusSeconds(31*60));run.setOwnerId(3L);run.setError("OWNER_INACTIVE");
            when(runs.listByTask(1L,12L,200,0)).thenReturn(List.of(run,terminalRun(66L)));
            when(dispatches.listLatestBySourceAndAgent(1L,ExecutionSourceType.SCHEDULED_TASK_RUN.name(),66L,20L,20)).thenReturn(List.of(sourceDispatch(88L)));
            when(selector.isAvailable(88L)).thenReturn(false);when(service.fencePendingContinuousResume(1L,77L)).thenReturn(true);
            var f=new com.aliyun.autowonder.notification.RecipientNotificationTestSupport();f.preference(3,"\"en-US\"");
            var redis=mock(com.aliyun.autowonder.redis.RedisManager.class);
            if(primary)recovery.setScheduledTaskNotificationService(new ScheduledTaskNotificationService(f.service,null,redis,runs));
            else recovery.setNotifyService(f.service);
            assertEquals(ScheduledTaskRunRecoveryService.State.DEGRADED,recovery.reconcile(run).state());
            assertEquals(1,f.rows.size());assertEquals("Source Executor wait timed out",f.rows.get(0).getContent());
            assertEquals(primary?"Scheduled task run status updated":"Scheduled task session resumed with reduced continuity",f.rows.get(0).getTitle());
            assertEquals(primary?"SCHEDULED_TASK_RUN_QUEUED":"SCHEDULED_TASK_RESUME_DEGRADED",f.rows.get(0).getType());
            assertEquals("SCHEDULED_TASK_RUN",f.rows.get(0).getRefType());assertEquals(77L,f.rows.get(0).getRefId());
            assertEquals("OWNER_INACTIVE",run.getError());assertEquals("QUEUED",run.getStatus());
            var order=inOrder(service,runs,f.dao);order.verify(service).fencePendingContinuousResume(1L,77L);
            order.verify(service).degradeResume(77L,900L,"SOURCE_EXECUTOR_TIMEOUT",1L);
            order.verify(runs).markDegraded(1L,77L,"SOURCE_EXECUTOR_TIMEOUT",66L,9,0L);order.verify(f.dao).insert(any());
            if(primary)verify(redis,times(1)).publish(eq("scheduled-run:77"),anyString());else verifyNoInteractions(redis);
        }
    }

    private static ScheduledTaskRunDO continuousRun(long id, Instant created) {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(id); run.setWorkspaceId(1L); run.setScheduledTaskId(12L); run.setInitialAgentId(20L);
        run.setStatus("QUEUED"); run.setVersion(9); run.setGmtCreate(Date.from(created));
        run.setExecutionSnapshotJson("{\"policies\":{\"sessionMode\":\"CONTINUOUS\",\"affinityTimeoutSeconds\":1800},\"agentContexts\":[{\"agentId\":20,\"agentVersionId\":401}]}" );
        return run;
    }
    private static ScheduledTaskRunDO terminalRun(long id) {
        ScheduledTaskRunDO run = continuousRun(id, Instant.EPOCH); run.setStatus("SUCCEEDED"); return run;
    }
    private static DispatchDO sourceDispatch(long executorId) {
        DispatchDO dispatch = new DispatchDO(); dispatch.setId(900L); dispatch.setExecutorId(executorId); dispatch.setAgentVersionId(401L); return dispatch;
    }
    private static void attachResumableCheckpoint(ScheduledTaskRunRecoveryService recovery) {
        DispatchCheckpointService checkpoint = mock(DispatchCheckpointService.class);
        when(checkpoint.hasResumableSession(1L, 900L)).thenReturn(true);
        recovery.setCheckpointService(checkpoint);
    }
}
