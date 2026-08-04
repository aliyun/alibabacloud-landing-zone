package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.integration.AoneWorkitemRefreshService;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import com.aliyun.autowonder.workitem.dto.TimelineItemVO;
import com.aliyun.autowonder.workitem.dto.WorkitemVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkitemControllerTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void readOnlyAccessSkipsExternalRefreshAndReturnsServiceData() {
        WorkitemService workitemService = mock(WorkitemService.class);
        AoneWorkitemRefreshService refreshService = mock(AoneWorkitemRefreshService.class);
        GuidanceService guidanceService = mock(GuidanceService.class);
        WorkitemController controller =
                new WorkitemController(workitemService, refreshService, guidanceService);
        setContext(OrgAccessLevel.READ_ONLY);
        WorkitemVO workitem = new WorkitemVO();
        List<CommentVO> comments = List.of(new CommentVO());
        List<TimelineItemVO> timeline = List.of(new TimelineItemVO());
        when(workitemService.get(1L)).thenReturn(workitem);
        when(workitemService.listComments(2L)).thenReturn(comments);
        when(workitemService.getUnifiedTimeline(3L)).thenReturn(timeline);

        assertSame(workitem, controller.get(1L).getData());
        assertSame(comments, controller.listComments(2L).getData());
        assertSame(timeline, controller.unifiedTimeline(3L).getData());

        verifyNoInteractions(refreshService);
        verify(guidanceService).attachInteractionStatuses(100L, 3L, timeline);
    }

    @Test
    void readWriteAccessRefreshesAllLinkedWorkitemReads() {
        WorkitemService workitemService = mock(WorkitemService.class);
        AoneWorkitemRefreshService refreshService = mock(AoneWorkitemRefreshService.class);
        GuidanceService guidanceService = mock(GuidanceService.class);
        WorkitemController controller =
                new WorkitemController(workitemService, refreshService, guidanceService);
        setContext(OrgAccessLevel.READ_WRITE);
        when(workitemService.listComments(2L)).thenReturn(List.of());
        when(workitemService.getUnifiedTimeline(3L)).thenReturn(List.of());

        controller.get(1L);
        controller.listComments(2L);
        controller.unifiedTimeline(3L);

        verify(refreshService).refreshIfLinked(1L, 100L, 7L);
        verify(refreshService).refreshIfLinked(2L, 100L, 7L);
        verify(refreshService).refreshIfLinked(3L, 100L, 7L);
    }

    private void setContext(OrgAccessLevel accessLevel) {
        AutoWonderContext.get().setCurrentOrgId(100L);
        AutoWonderContext.get().setUserId(7L);
        AutoWonderContext.get().setOrgAccessLevel(accessLevel);
    }
}
