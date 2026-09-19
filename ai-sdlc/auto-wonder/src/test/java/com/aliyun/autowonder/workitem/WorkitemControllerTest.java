package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import com.aliyun.autowonder.workitem.dto.TimelineItemVO;
import com.aliyun.autowonder.workitem.dto.WorkitemVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkitemControllerTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void readsReturnServiceDataWithoutExternalRefresh() {
        WorkitemService workitemService = mock(WorkitemService.class);
        GuidanceService guidanceService = mock(GuidanceService.class);
        WorkitemWatcherService watcherService = mock(WorkitemWatcherService.class);
        WorkitemController controller = new WorkitemController(workitemService, guidanceService, watcherService);
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
    }

    @Test
    void transitionForwardsDragSnapshotToServiceGate() {
        WorkitemService service = mock(WorkitemService.class);
        WorkitemController controller = new WorkitemController(service, mock(GuidanceService.class),
                mock(WorkitemWatcherService.class));
        setContext(WorkspaceAccessLevel.READ_WRITE);
        var request = new com.aliyun.autowonder.workitem.dto.TransitionRequest();
        request.setToNodeId(21L);
        request.setFromNodeId(20L);
        request.setExpectedVersion(3);
        controller.transition(1L, request);
        verify(service).transition(1L, 21L, 100L, 7L, 20L, 3);
    }

    private void setContext(WorkspaceAccessLevel accessLevel) {
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        AutoWonderContext.get().setUserId(7L);
        AutoWonderContext.get().setWorkspaceAccessLevel(accessLevel);
    }
}
