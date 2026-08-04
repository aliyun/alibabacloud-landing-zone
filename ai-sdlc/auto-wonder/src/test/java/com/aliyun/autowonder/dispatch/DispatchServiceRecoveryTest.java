package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.taskpackage.TaskPackager;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchServiceRecoveryTest {

    private DispatchDao dispatchDao;
    private RedisManager redisManager;
    private DispatchService service;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        redisManager = mock(RedisManager.class);
        DispatchService real = new DispatchService(dispatchDao, mock(DispatchRuntimeEventDao.class),
                mock(WorkitemDao.class), mock(AgentDao.class), mock(AgentVersionDao.class),
                mock(ExecutorSelector.class), mock(PackageContextAssembler.class),
                mock(TaskPackager.class), mock(DispatchTransport.class), mock(SdlcDriver.class),
                redisManager, mock(DispatchCheckpointService.class));
        service = spy(real);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        doReturn(false).when(service).runPending(anyLong());
    }

    @Test
    void createsNewFencedRecoveryAttemptFromLatestFailedDispatch() {
        DispatchDO source = dispatch(55L, DispatchStatus.FAILED, 2);
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(List.of(source));
        when(dispatchDao.findByIdempotencyKey(100L, "continue:55")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(100L, 200L, 300L)).thenReturn(2);
        doAnswer(invocation -> {
            DispatchDO inserted = invocation.getArgument(0);
            inserted.setId(56L);
            return null;
        }).when(dispatchDao).insert(any(DispatchDO.class));

        DispatchDO recovery = service.continueDispatch(100L, 200L, 55L, 9L);

        assertEquals(56L, recovery.getId());
        assertEquals(3, recovery.getAttempt());
        assertEquals(55L, recovery.getResumeFromDispatchId());
        assertEquals("RECOVERY", recovery.getResumeMode());
        assertEquals("continue:55", recovery.getIdempotencyKey());
        verify(service).runPending(56L);
    }

    @Test
    void staleRunningDispatchIsCancelledBeforeRecovery() {
        DispatchDO source = dispatch(55L, DispatchStatus.RUNNING, 1);
        source.setGmtModified(new Date(System.currentTimeMillis() - 180_000L));
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(List.of(source));
        when(dispatchDao.findByIdempotencyKey(100L, "continue:55")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(100L, 200L, 300L)).thenReturn(1);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.CANCELED),
                any(), any(), any(), any(), eq(DispatchFailureReason.MANUAL_CONTINUE),
                eq(0), anyLong())).thenReturn(1);
        doAnswer(invocation -> {
            DispatchDO inserted = invocation.getArgument(0);
            inserted.setId(56L);
            return null;
        }).when(dispatchDao).insert(any(DispatchDO.class));

        service.continueDispatch(100L, 200L, 55L, 9L);

        verify(dispatchDao).updateStatus(eq(55L), eq(100L), eq(DispatchStatus.CANCELED),
                any(), any(), any(), any(), eq(DispatchFailureReason.MANUAL_CONTINUE),
                eq(0), anyLong());
    }

    @Test
    void pausedDispatchCanContinueImmediately() {
        DispatchDO source = dispatch(55L, DispatchStatus.PAUSED, 2);
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(List.of(source));
        when(dispatchDao.findByIdempotencyKey(100L, "continue:55")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(100L, 200L, 300L)).thenReturn(2);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.CANCELED),
                isNull(), isNull(), isNull(), isNull(), eq(DispatchFailureReason.MANUAL_CONTINUE),
                eq(0), eq(0L))).thenReturn(1);
        doAnswer(invocation -> {
            DispatchDO inserted = invocation.getArgument(0);
            inserted.setId(56L);
            return null;
        }).when(dispatchDao).insert(any(DispatchDO.class));

        DispatchDO recovery = service.continueDispatch(100L, 200L, 55L, 9L);

        assertEquals(55L, recovery.getResumeFromDispatchId());
        assertEquals("RECOVERY", recovery.getResumeMode());
        verify(dispatchDao).updateStatus(eq(55L), eq(100L), eq(DispatchStatus.CANCELED),
                isNull(), isNull(), isNull(), isNull(), eq(DispatchFailureReason.MANUAL_CONTINUE),
                eq(0), eq(0L));
    }

    private DispatchDO dispatch(long id, String status, int attempt) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(100L);
        dispatch.setWorkitemId(200L);
        dispatch.setSdlcStepId(300L);
        dispatch.setAgentId(400L);
        dispatch.setStatus(status);
        dispatch.setAttempt(attempt);
        dispatch.setVersion(0);
        dispatch.setGmtModified(new Date());
        return dispatch;
    }
}
