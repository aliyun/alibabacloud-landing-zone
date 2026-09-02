package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.websocket.PresenceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DispatchRecoveryControllerTest {

    @AfterEach
    void clearContext() {
        AutoWonderContext.destroy();
    }

    @Test
    void continueRejectsScheduledRunBeforeExecutorPresenceCheck() {
        DispatchService dispatchService = mock(DispatchService.class);
        PresenceManager presence = mock(PresenceManager.class);
        DispatchDO scheduled = new DispatchDO();
        scheduled.setId(55L);
        scheduled.setTenantId(100L);
        scheduled.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        scheduled.setWorkitemId(200L);
        scheduled.setExecutorId(7L);
        scheduled.setStatus(DispatchStatus.RUNNING);
        when(dispatchService.loadForTenant(55L)).thenReturn(scheduled);
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        AutoWonderContext.get().setUserId(9L);
        DispatchRecoveryController controller = new DispatchRecoveryController(
                dispatchService, presence, mock(DispatchPauseService.class));

        assertThrows(BizException.class, () -> controller.continueDispatch(200L, 55L));

        verifyNoInteractions(presence);
        verify(dispatchService, never()).continueDispatch(anyLong(), anyLong(), anyLong(), anyLong());
    }
}
