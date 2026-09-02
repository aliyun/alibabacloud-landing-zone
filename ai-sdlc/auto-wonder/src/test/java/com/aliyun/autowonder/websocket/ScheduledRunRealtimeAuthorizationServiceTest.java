package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDO;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDao;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduledRunRealtimeAuthorizationServiceTest {
    @Test
    void unavailableCapabilityFailsBeforeScheduledRunLookup() {
        ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
        WorkspaceMemberDao members = mock(WorkspaceMemberDao.class);
        ScheduledTaskCapabilityGuard guard = mock(ScheduledTaskCapabilityGuard.class);
        doThrow(new BizException(ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY))
                .when(guard).requireAvailable("realtime");

        BizException failure = assertThrows(BizException.class,
                () -> new ScheduledRunRealtimeAuthorizationService(runs, members, guard)
                        .authorize(7L, 8L, "scheduled-run:9"));

        assertEquals("30006", failure.getCode());
        verifyNoInteractions(runs, members);
    }

    @Test
    void allowsLiveMemberForTenantScopedRunOnly() {
        ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
        WorkspaceMemberDao members = mock(WorkspaceMemberDao.class);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO(); run.setWorkspaceId(7L);
        WorkspaceMemberDO member = new WorkspaceMemberDO(); member.setStatus(1); member.setAccessLevel(WorkspaceAccessLevel.READ_ONLY.name());
        when(runs.findById(7L, 9L)).thenReturn(run);
        when(members.findByWorkspaceAndUser(7L, 8L)).thenReturn(member);
        ScheduledTaskCapabilityGuard guard = mock(ScheduledTaskCapabilityGuard.class);
        ScheduledRunRealtimeAuthorizationService service = new ScheduledRunRealtimeAuthorizationService(runs, members, guard);
        assertTrue(service.authorize(7L, 8L, "scheduled-run:9"));
        assertFalse(service.authorize(8L, 8L, "scheduled-run:9"));
        assertFalse(service.authorize(7L, 8L, "scheduled-run:x"));
        member.setAccessLevel("NONE");
        assertFalse(service.authorize(7L, 8L, "scheduled-run:9"));
    }
}
