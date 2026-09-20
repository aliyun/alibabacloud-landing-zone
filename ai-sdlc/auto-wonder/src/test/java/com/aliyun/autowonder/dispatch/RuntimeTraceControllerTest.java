package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dispatch.dto.RuntimeActivityTimelineVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuntimeTraceControllerTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void eventLogUsesPersistedEventsAndCursorInsteadOfCompactArtifactOutline() {
        RuntimeTraceService service = mock(RuntimeTraceService.class);
        RuntimeTraceArtifactService artifacts = mock(RuntimeTraceArtifactService.class);
        var trace = new com.aliyun.autowonder.dispatch.dto.RuntimeTraceVO();
        AutoWonderContext.get().setCurrentWorkspaceId(73L);
        when(service.get(73L, 301L, 100L)).thenReturn(trace);
        var controller = new RuntimeTraceController(service, artifacts, mock(DispatchLiveActivityService.class));
        assertSame(trace, controller.events(301L, 100L).getData());
        verify(service).get(73L, 301L, 100L);
        verifyNoInteractions(artifacts);
    }

    @Test
    void returnsTheCompleteActivitiesSnapshotForTheCurrentWorkspace() {
        RuntimeTraceService traceService = mock(RuntimeTraceService.class);
        RuntimeTraceArtifactService artifactService = mock(RuntimeTraceArtifactService.class);
        RuntimeActivityTimelineVO timeline = new RuntimeActivityTimelineVO();
        timeline.setDispatchId(301L);
        AutoWonderContext.get().setCurrentWorkspaceId(73L);
        when(traceService.getActivities(73L, 301L)).thenReturn(timeline);

        RuntimeTraceController controller = new RuntimeTraceController(traceService, artifactService, mock(DispatchLiveActivityService.class));

        assertSame(timeline, controller.activities(301L).getData());
        verify(traceService).getActivities(73L, 301L);
        verifyNoInteractions(artifactService);
    }
}
