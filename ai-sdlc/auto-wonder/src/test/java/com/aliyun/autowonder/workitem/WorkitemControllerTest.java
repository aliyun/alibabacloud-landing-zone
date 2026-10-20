package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import com.aliyun.autowonder.workitem.dto.TimelineItemVO;
import com.aliyun.autowonder.workitem.dto.WorkitemOwningWorkspaceVO;
import com.aliyun.autowonder.workitem.dto.WorkitemVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkitemControllerTest {

    private static final long WORKITEM_ID = 55698L;

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void readsReturnServiceDataWithoutExternalRefresh() {
        WorkitemService workitemService = mock(WorkitemService.class);
        GuidanceService guidanceService = mock(GuidanceService.class);
        WorkitemWatcherService watcherService = mock(WorkitemWatcherService.class);
        WorkitemOwningWorkspaceResolver resolver = mock(WorkitemOwningWorkspaceResolver.class);
        WorkitemController controller =
                new WorkitemController(workitemService, guidanceService, watcherService, resolver);
        setContext(WorkspaceAccessLevel.READ_ONLY);
        WorkitemVO workitem = new WorkitemVO();
        List<CommentVO> comments = List.of(new CommentVO());
        List<TimelineItemVO> timeline = List.of(new TimelineItemVO());
        when(workitemService.get(1L)).thenReturn(workitem);
        when(workitemService.listComments(2L)).thenReturn(comments);
        when(workitemService.getUnifiedTimeline(3L)).thenReturn(timeline);

        assertSame(workitem, controller.get(1L).getData());
        assertSame(comments, controller.listComments(2L).getData());
        assertSame(timeline, controller.unifiedTimeline(3L).getData());

        verify(guidanceService).attachInteractionStatuses(100L, 3L, timeline);
        verifyNoInteractions(resolver);
    }

    @Test
    void transitionForwardsDragSnapshotToServiceGate() {
        WorkitemService service = mock(WorkitemService.class);
        WorkitemController controller = new WorkitemController(service, mock(GuidanceService.class),
                mock(WorkitemWatcherService.class), mock(WorkitemOwningWorkspaceResolver.class));
        setContext(WorkspaceAccessLevel.READ_WRITE);
        var request = new com.aliyun.autowonder.workitem.dto.TransitionRequest();
        request.setToNodeId(21L);
        request.setFromNodeId(20L);
        request.setExpectedVersion(3);
        controller.transition(1L, request);
        verify(service).transition(1L, 21L, 100L, 7L, 20L, 3);
    }

    @Test
    void missingWorkitemOwnedByAccessibleWorkspaceAsksToSwitchInsteadOfNotFound() {
        WorkitemService service = mock(WorkitemService.class);
        WorkitemOwningWorkspaceResolver resolver = mock(WorkitemOwningWorkspaceResolver.class);
        WorkitemController controller = new WorkitemController(service, mock(GuidanceService.class),
                mock(WorkitemWatcherService.class), resolver);
        setContext(WorkspaceAccessLevel.READ_ONLY);
        when(service.get(WORKITEM_ID)).thenThrow(new BizException(ErrorCode.WORKITEM_NOT_FOUND));
        when(resolver.resolve(WORKITEM_ID, 7L, 100L)).thenReturn(Optional.of(owning(200L, "项目B")));

        WorkitemInOtherWorkspaceException thrown = assertThrows(WorkitemInOtherWorkspaceException.class,
                () -> controller.get(WORKITEM_ID));

        assertEquals(WORKITEM_ID, thrown.getWorkitemId());
        assertEquals(200L, thrown.getWorkspaceId());
        assertEquals("项目B", thrown.getWorkspaceName());
    }

    @Test
    void missingWorkitemWithoutAccessibleOwningWorkspaceKeepsNotFound() {
        WorkitemService service = mock(WorkitemService.class);
        WorkitemOwningWorkspaceResolver resolver = mock(WorkitemOwningWorkspaceResolver.class);
        WorkitemController controller = new WorkitemController(service, mock(GuidanceService.class),
                mock(WorkitemWatcherService.class), resolver);
        setContext(WorkspaceAccessLevel.READ_ONLY);
        BizException notFound = new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        when(service.get(WORKITEM_ID)).thenThrow(notFound);
        when(resolver.resolve(WORKITEM_ID, 7L, 100L)).thenReturn(Optional.empty());

        BizException thrown = assertThrows(BizException.class, () -> controller.get(WORKITEM_ID));

        assertSame(notFound, thrown);
    }

    @Test
    void readErrorsOtherThanMissingNeverTriggerOwnershipProbing() {
        WorkitemService service = mock(WorkitemService.class);
        WorkitemOwningWorkspaceResolver resolver = mock(WorkitemOwningWorkspaceResolver.class);
        WorkitemController controller = new WorkitemController(service, mock(GuidanceService.class),
                mock(WorkitemWatcherService.class), resolver);
        setContext(WorkspaceAccessLevel.READ_ONLY);
        BizException denied = new BizException(ErrorCode.NO_PERMISSION);
        when(service.get(WORKITEM_ID)).thenThrow(denied);

        BizException thrown = assertThrows(BizException.class, () -> controller.get(WORKITEM_ID));

        assertSame(denied, thrown);
        verify(resolver, never()).resolve(WORKITEM_ID, 7L, 100L);
    }

    private static WorkitemOwningWorkspaceVO owning(long workspaceId, String workspaceName) {
        WorkitemOwningWorkspaceVO owning = new WorkitemOwningWorkspaceVO();
        owning.setWorkspaceId(workspaceId);
        owning.setWorkspaceName(workspaceName);
        return owning;
    }

    @Test
    void listForwardsCreatedTimeBoundsToService() {
        WorkitemService service = mock(WorkitemService.class);
        WorkitemController controller = new WorkitemController(service, mock(GuidanceService.class),
                mock(WorkitemWatcherService.class), mock(WorkitemOwningWorkspaceResolver.class));
        setContext(WorkspaceAccessLevel.READ_ONLY);
        PageResult<WorkitemVO> page = new PageResult<>(List.of(), 0L, 1, 20);
        when(service.list(null, null, null, null, null, false, null, 100L, 7L, null, null, null,
                "2026-10-01T00:00:00.000+08:00", "2026-10-10T00:00:00.000+08:00", 1, 20)).thenReturn(page);

        assertSame(page, controller.list(null, null, null, null, null, false, null, null, null, null,
                "2026-10-01T00:00:00.000+08:00", "2026-10-10T00:00:00.000+08:00", 1, 20).getData());
    }

    private void setContext(WorkspaceAccessLevel accessLevel) {
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        AutoWonderContext.get().setUserId(7L);
        AutoWonderContext.get().setWorkspaceAccessLevel(accessLevel);
    }
}
