package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * F2.4/D5: what a logical delete has to stop. The two halves are tested separately because they run
 * at different times — the timers inside the delete transaction, the dispatches after it commits.
 */
class WorkspaceDeletionLinkageTest {

    private static final long WORKSPACE_ID = 10L;
    private static final long OPERATOR_ID = 7L;

    private ScheduledTaskDao scheduledTaskDao;
    private DispatchDao dispatchDao;
    private DispatchPauseService dispatchPauseService;
    private JdbcTemplate jdbc;
    private WorkspaceDeletionLinkage linkage;

    @BeforeEach
    void setUp() {
        scheduledTaskDao = mock(ScheduledTaskDao.class);
        dispatchDao = mock(DispatchDao.class);
        dispatchPauseService = mock(DispatchPauseService.class);
        jdbc = mock(JdbcTemplate.class);
        linkage = new WorkspaceDeletionLinkage(scheduledTaskDao, dispatchDao, dispatchPauseService, jdbc);
        // stopInFlightDispatches iterates the result directly, so the default has to be an empty
        // list rather than the null a bare mock returns.
        when(dispatchDao.listInFlightByTenant(anyLong(), anyCollection(), anyInt()))
                .thenReturn(List.of());
    }

    @Test
    void thePauseReasonMatchesTheWordTheDeleteDialogPromises() {
        // The confirm dialog tells the operator exactly this, and it is what lands in dispatch.error
        // and the delete audit entry, so the three cannot drift apart.
        assertEquals("工作空间已删除", WorkspaceDeletionLinkage.DELETION_REASON);
    }

    @Test
    void pausingScheduledTasksIsOneBulkStatementScopedToTheWorkspace() {
        when(scheduledTaskDao.pauseActiveByWorkspace(WORKSPACE_ID, OPERATOR_ID)).thenReturn(4);

        assertEquals(4, linkage.pauseScheduledTasks(WORKSPACE_ID, OPERATOR_ID));

        verify(scheduledTaskDao).pauseActiveByWorkspace(WORKSPACE_ID, OPERATOR_ID);
        // No remote call belongs in the transaction that holds the org row lock.
        verifyNoInteractions(dispatchDao);
        verifyNoInteractions(dispatchPauseService);
    }

    @Test
    void pausingScheduledTasksReportsZeroWithoutTouchingTheDispatchHalf() {
        when(scheduledTaskDao.pauseActiveByWorkspace(WORKSPACE_ID, OPERATOR_ID)).thenReturn(0);

        assertEquals(0, linkage.pauseScheduledTasks(WORKSPACE_ID, OPERATOR_ID));
    }

    @Test
    void disablingSharedBindingsStopsBothPlatformsForThisWorkspaceOnly() {
        // any(Object.class) keeps this bound to update(String, Object...) instead of the
        // update(String, Object[], int[]) overload the untyped any() would infer.
        when(jdbc.update(anyString(), any(Object.class), any(Object.class)))
                .thenReturn(2).thenReturn(1);

        assertEquals(3, linkage.disableSharedBindings(WORKSPACE_ID, OPERATOR_ID));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), any(Object.class), any(Object.class));
        String feishu = sql.getAllValues().get(0);
        String dingtalk = sql.getAllValues().get(1);
        // Only platform SHARED bots are switched off; a LEGACY binding owns its own credentials
        // and must stay untouched so an operator can still decide about it explicitly.
        assertTrue(feishu.contains("UPDATE feishu_robot_binding"));
        assertTrue(feishu.contains("binding_mode='SHARED'"));
        assertTrue(feishu.contains("status<>'DISABLED'"));
        // A logically deleted dingtalk row would otherwise be revived by a later restore.
        assertTrue(dingtalk.contains("UPDATE dingtalk_robot_binding"));
        assertTrue(dingtalk.contains("binding_mode='SHARED'"));
        assertTrue(dingtalk.contains("is_deleted=0"));
        assertFalse(feishu.contains("dingtalk_robot_binding"));
    }

    @Test
    void disablingSharedBindingsScopesEveryStatementToTheDeletedWorkspace() {
        when(jdbc.update(anyString(), any(Object.class), any(Object.class))).thenReturn(1, 1);

        linkage.disableSharedBindings(WORKSPACE_ID, OPERATOR_ID);

        ArgumentCaptor<Object> params = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("feishu_robot_binding"), params.capture(), params.capture());
        assertEquals(List.of(OPERATOR_ID, WORKSPACE_ID), params.getAllValues());
        verify(jdbc).update(contains("dingtalk_robot_binding"), eq(OPERATOR_ID), eq(WORKSPACE_ID));
    }

    @Test
    void disablingSharedBindingsReportsZeroWhenNoSharedBotIsEnabled() {
        when(jdbc.update(anyString(), any(Object.class), any(Object.class))).thenReturn(0);

        assertEquals(0, linkage.disableSharedBindings(WORKSPACE_ID, OPERATOR_ID));
    }

    @Test
    void stoppingDispatchesFetchesOnlyTheInFlightStatusesAndBoundsTheBatch() {
        linkage.stopInFlightDispatches(WORKSPACE_ID, OPERATOR_ID);

        // PAUSED/SUCCEEDED/FAILED/TIMEOUT/CANCELED are already stopped, so re-touching them would
        // overwrite a real outcome with the deletion reason. The limit keeps one deleted workspace
        // from loading an unbounded batch into memory.
        verify(dispatchDao).listInFlightByTenant(eq(WORKSPACE_ID), argThat(statuses ->
                Set.copyOf(statuses).equals(Set.of(
                        DispatchStatus.PENDING,
                        DispatchStatus.PACKAGING,
                        DispatchStatus.DISPATCHED,
                        DispatchStatus.ACKED,
                        DispatchStatus.RUNNING,
                        DispatchStatus.PAUSING,
                        DispatchStatus.PAUSE_FAILED,
                        DispatchStatus.WAITING_FOR_PAUSE))), eq(500));
        verifyNoInteractions(dispatchPauseService);
    }

    @Test
    void stoppingDispatchesIsANoOpWhenNothingIsInFlight() {
        assertEquals(0, linkage.stopInFlightDispatches(WORKSPACE_ID, OPERATOR_ID));

        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(), any(),
                any(), any(), anyString(), any(), anyLong());
        verifyNoInteractions(dispatchPauseService);
    }

    @ParameterizedTest
    @ValueSource(strings = {DispatchStatus.PENDING, DispatchStatus.PACKAGING,
            DispatchStatus.WAITING_FOR_PAUSE})
    void dispatchesThatNeverReachedAnExecutorAreCanceledWithTheDeletionReason(String status) {
        given(dispatch(101L, status, null));
        when(dispatchDao.updateStatus(eq(101L), eq(WORKSPACE_ID), eq(DispatchStatus.CANCELED),
                isNull(), isNull(), isNull(), isNull(),
                eq(WorkspaceDeletionLinkage.DELETION_REASON), eq(4), eq(OPERATOR_ID)))
                .thenReturn(1);

        assertEquals(1, linkage.stopInFlightDispatches(WORKSPACE_ID, OPERATOR_ID));

        // F2.4: nothing was handed to an executor yet, so there is nobody to ask for a pause —
        // cancel outright and record why.
        verify(dispatchDao).updateStatus(101L, WORKSPACE_ID, DispatchStatus.CANCELED, null, null,
                null, null, WorkspaceDeletionLinkage.DELETION_REASON, 4, OPERATOR_ID);
        verifyNoInteractions(dispatchPauseService);
    }

    @Test
    void aCancelThatLostItsVersionRaceIsNotCountedAsStopped() {
        given(dispatch(101L, DispatchStatus.PENDING, null));
        when(dispatchDao.updateStatus(anyLong(), anyLong(), anyString(), any(), any(), any(), any(),
                anyString(), any(), anyLong())).thenReturn(0);

        // The status predicate in the UPDATE already moved on, so this dispatch is somebody else's
        // to explain — reporting it as stopped would hide the leftover.
        assertEquals(0, linkage.stopInFlightDispatches(WORKSPACE_ID, OPERATOR_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {DispatchStatus.DISPATCHED, DispatchStatus.ACKED, DispatchStatus.RUNNING,
            DispatchStatus.PAUSING, DispatchStatus.PAUSE_FAILED})
    void dispatchesAnExecutorAlreadyOwnsGoThroughTheExistingPauseSemantics(String status) {
        given(dispatch(102L, status, null), dispatch(103L, status, "WORKITEM"));

        assertEquals(2, linkage.stopInFlightDispatches(WORKSPACE_ID, OPERATOR_ID));

        // F2.4: reuse the pause path rather than inventing a cancel, so an executor mid-step is
        // stopped where it can be resumed after a restore.
        verify(dispatchPauseService).requestPause(WORKSPACE_ID, 602L, 102L, OPERATOR_ID);
        verify(dispatchPauseService).requestPause(WORKSPACE_ID, 603L, 103L, OPERATOR_ID);
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(), any(),
                any(), any(), anyString(), any(), anyLong());
    }

    @Test
    void aScheduledRunDispatchIsPausedThroughTheRunVariant() {
        given(dispatch(104L, DispatchStatus.RUNNING, "SCHEDULED_TASK_RUN"),
                dispatch(105L, DispatchStatus.RUNNING, "SCHEDULED_TASK"));

        assertEquals(2, linkage.stopInFlightDispatches(WORKSPACE_ID, OPERATOR_ID));

        // The run variant pauses the ScheduledTaskRun snapshot; the plain one pauses the workitem
        // dispatch. Sending a run down the wrong one leaves the run stuck in RUNNING.
        verify(dispatchPauseService).requestPauseScheduledRun(WORKSPACE_ID, 604L, 104L, OPERATOR_ID);
        verify(dispatchPauseService).requestPause(WORKSPACE_ID, 605L, 105L, OPERATOR_ID);
    }

    @Test
    void oneFailingDispatchNeverAbortsTheOthersOrUndoesTheDelete() {
        given(dispatch(106L, DispatchStatus.RUNNING, null),
                dispatch(107L, DispatchStatus.PENDING, null),
                dispatch(108L, DispatchStatus.RUNNING, null));
        doThrow(new IllegalStateException("executor unreachable"))
                .when(dispatchPauseService).requestPause(WORKSPACE_ID, 606L, 106L, OPERATOR_ID);
        when(dispatchDao.updateStatus(eq(107L), anyLong(), anyString(), any(), any(), any(), any(),
                anyString(), any(), anyLong())).thenReturn(1);

        // The workspace is already deleted and the delete cannot be rolled back, so a leftover is
        // logged and left to the stuck-dispatch compensation instead of failing the request.
        assertEquals(2, linkage.stopInFlightDispatches(WORKSPACE_ID, OPERATOR_ID));

        verify(dispatchPauseService).requestPause(WORKSPACE_ID, 608L, 108L, OPERATOR_ID);
        verify(dispatchDao).updateStatus(eq(107L), eq(WORKSPACE_ID), eq(DispatchStatus.CANCELED),
                isNull(), isNull(), isNull(), isNull(),
                eq(WorkspaceDeletionLinkage.DELETION_REASON), eq(4), eq(OPERATOR_ID));
    }

    private void given(DispatchDO... dispatches) {
        when(dispatchDao.listInFlightByTenant(anyLong(), anyCollection(), anyInt()))
                .thenReturn(List.of(dispatches));
    }

    private static DispatchDO dispatch(long id, String status, String sourceType) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(WORKSPACE_ID);
        dispatch.setWorkitemId(500L + id);
        dispatch.setStatus(status);
        dispatch.setSourceType(sourceType);
        dispatch.setVersion(4);
        return dispatch;
    }
}
