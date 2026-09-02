package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduledTaskRunServiceTest {
    @Test
    void progressBridgesOnlyWaitingScheduledRunToRunning() {
        ScheduledTaskRunDao dao = mock(ScheduledTaskRunDao.class);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO(); run.setId(77L); run.setWorkspaceId(1L);
        run.setStatus("WAITING_EXECUTOR"); run.setVersion(3);
        DispatchDO dispatch = new DispatchDO(); dispatch.setTenantId(1L); dispatch.setWorkitemId(77L);
        dispatch.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        when(dao.findById(1L, 77L)).thenReturn(run);

        new ScheduledTaskRunService(dao).markRunningFromDispatch(dispatch);

        verify(dao).updateStatus(1L, 77L, "WAITING_EXECUTOR", "RUNNING", 3, 0L);
    }

    @Test
    void cancelUsesTheTerminalMutationPath() {
        ScheduledTaskRunDao dao = mock(ScheduledTaskRunDao.class);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO(); run.setId(77L); run.setWorkspaceId(1L);
        run.setStatus("RUNNING"); run.setVersion(3);
        when(dao.findById(1L, 77L)).thenReturn(run);
        when(dao.updateTerminalResult(1L, 77L, "RUNNING", "CANCELED", null, "CANCELED", 3, 9L))
                .thenReturn(1);

        ScheduledTaskRunDO canceled = new ScheduledTaskRunService(dao)
                .transition(1L, 77L, 3, "CANCELED", 9L);

        assertEquals("CANCELED", canceled.getStatus());
        verify(dao).updateTerminalResult(1L, 77L, "RUNNING", "CANCELED", null, "CANCELED", 3, 9L);
        verify(dao, never()).updateStatus(anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyLong());
    }
}
