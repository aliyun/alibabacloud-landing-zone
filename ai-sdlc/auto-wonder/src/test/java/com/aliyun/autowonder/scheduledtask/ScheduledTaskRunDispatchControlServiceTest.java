package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.dispatch.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduledTaskRunDispatchControlServiceTest {
    @Test void pauseUsesSourceScopedDispatchAndRuntimePauseControl() {
        DispatchDao dao = mock(DispatchDao.class); DispatchPauseService pauses = mock(DispatchPauseService.class);
        DispatchDO running = new DispatchDO(); running.setId(5L); running.setStatus(DispatchStatus.RUNNING);
        when(dao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 9L)).thenReturn(List.of(running));
        assertTrue(new ScheduledTaskRunDispatchControlService(dao, pauses).pauseActive(1L, 9L, 7L, true));
        verify(pauses).requestPauseScheduledRun(1L, 9L, 5L, 7L);
        verify(dao, never()).listByWorkitem(anyLong(), anyLong());
    }
}
