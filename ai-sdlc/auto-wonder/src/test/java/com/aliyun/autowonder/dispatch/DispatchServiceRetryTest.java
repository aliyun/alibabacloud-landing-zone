package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.taskpackage.TaskPackager;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchServiceRetryTest {

    private DispatchDao dispatchDao;
    private SdlcDriver sdlcDriver;
    private DispatchService service;

    private static final long TENANT = 100L;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        AgentVersionDao agentVersionDao = mock(AgentVersionDao.class);
        ExecutorSelector executorSelector = mock(ExecutorSelector.class);
        PackageContextAssembler assembler = mock(PackageContextAssembler.class);
        TaskPackager taskPackager = mock(TaskPackager.class);
        DispatchTransport transport = mock(DispatchTransport.class);
        sdlcDriver = mock(SdlcDriver.class);
        RedisManager redisManager = mock(RedisManager.class);
        service = new DispatchService(dispatchDao, mock(DispatchRuntimeEventDao.class), workitemDao, agentDao, agentVersionDao,
                executorSelector, assembler, taskPackager, transport, sdlcDriver,
                redisManager);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(dispatchDao.updateStatus(anyLong(), anyLong(), anyString(), any(), any(),
                any(), any(), any(), anyInt(), anyLong())).thenReturn(1);
        when(dispatchDao.findById(argThat(id -> id != 500L))).thenReturn(null);
        when(sdlcDriver.onFail(anyLong(), anyLong(), anyLong())).thenReturn(DriveResult.stop());
    }

    private DispatchDO failed(int attempt) {
        DispatchDO d = new DispatchDO();
        d.setId(500L);
        d.setTenantId(TENANT);
        d.setWorkitemId(200L);
        d.setSdlcStepId(300L);
        d.setAgentId(400L);
        d.setStatus(DispatchStatus.FAILED);
        d.setAttempt(attempt);
        d.setVersion(1);
        return d;
    }

    @Test
    void retryEnqueuesNextAttemptWhenBudgetRemains() {
        doAnswer(inv -> { ((DispatchDO) inv.getArgument(0)).setId(501L); return null; })
                .when(dispatchDao).insert(any());
        when(dispatchDao.findByIdempotencyKey(TENANT, "200:300:2")).thenReturn(null);
        service.retry(failed(1), 3);
        verify(dispatchDao).insert(argThat(d ->
                d.getAttempt() == 2 && "200:300:2".equals(d.getIdempotencyKey())
                        && ExecutionSourceType.WORKITEM.name().equals(d.getSourceType())
                        && DispatchStatus.PENDING.equals(d.getStatus())));
    }

    @Test
    void retryHandsToHumanWhenBudgetExhausted() {
        service.retry(failed(3), 3);
        verify(dispatchDao, never()).insert(any());
        verify(sdlcDriver).onFail(TENANT, 200L, 300L);
    }

    @Test
    void onTimeoutTerminatesNonTerminalAndDrivesFail() {
        DispatchDO d = new DispatchDO();
        d.setId(500L);
        d.setTenantId(TENANT);
        d.setWorkitemId(200L);
        d.setSdlcStepId(300L);
        d.setStatus(DispatchStatus.RUNNING);
        d.setAttempt(1);
        d.setVersion(0);
        when(dispatchDao.findById(500L)).thenReturn(d);
        service.onTimeout(TENANT, 500L);
        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.TIMEOUT),
                any(), any(), any(), any(), eq(DispatchFailureReason.TIMEOUT), anyInt(), anyLong());
        verify(sdlcDriver).onFail(TENANT, 200L, 300L);
    }

    @Test
    void onTimeoutIgnoredWhenTerminal() {
        DispatchDO d = failed(1);
        when(dispatchDao.findById(500L)).thenReturn(d);
        service.onTimeout(TENANT, 500L);
        verify(sdlcDriver, never()).onFail(anyLong(), anyLong(), anyLong());
    }

    @Test
    void onTimeoutNeverTerminalizesPreAckStates() {
        for (String status : java.util.List.of(
                DispatchStatus.PENDING, DispatchStatus.PACKAGING, DispatchStatus.DISPATCHED)) {
            DispatchDO d = new DispatchDO();
            d.setId(500L);
            d.setTenantId(TENANT);
            d.setWorkitemId(200L);
            d.setSdlcStepId(300L);
            d.setStatus(status);
            d.setVersion(0);
            when(dispatchDao.findById(500L)).thenReturn(d);

            service.onTimeout(TENANT, 500L);
        }

        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), eq(DispatchStatus.TIMEOUT),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
        verify(sdlcDriver, never()).onFail(anyLong(), anyLong(), anyLong());
    }
}
