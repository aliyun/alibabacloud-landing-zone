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

    @Test
    void contextValidationKeepsHttp200CodeAndRawDiagnosticWithEnglishBoundary() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(73L);
        var artifacts = new RuntimeTraceArtifactService(mock(com.aliyun.autowonder.artifact.ArtifactDao.class), mock(com.aliyun.autowonder.storage.ObjectStorage.class));
        var controller = new RuntimeTraceController(mock(RuntimeTraceService.class), artifacts, mock(DispatchLiveActivityService.class));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new com.aliyun.autowonder.common.web.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/dispatches/301/runtime-trace/context")
            .param("ref","../trace.json").header("Accept-Language","en-US"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("10001"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("The context content reference is invalid."));
    }
    @Test
    void contextSuccessRetainsOpaqueBytesAndNosniffHeader() {
        AutoWonderContext.get().setCurrentWorkspaceId(73L);
        var artifacts = mock(RuntimeTraceArtifactService.class);
        byte[] bytes = new byte[]{0, (byte)0xff, 42};
        when(artifacts.loadContext(73L,301L,"context/files/raw")).thenReturn(new RuntimeTraceArtifactService.ContextContent("context/files/raw",bytes));
        var controller = new RuntimeTraceController(mock(RuntimeTraceService.class), artifacts, mock(DispatchLiveActivityService.class));
        var response = controller.getContext(301L,"context/files/raw");
        org.junit.jupiter.api.Assertions.assertEquals(200,response.getStatusCodeValue());
        org.junit.jupiter.api.Assertions.assertEquals("nosniff",response.getHeaders().getFirst("X-Content-Type-Options"));
        org.junit.jupiter.api.Assertions.assertEquals(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM,response.getHeaders().getContentType());
        org.junit.jupiter.api.Assertions.assertArrayEquals(bytes,response.getBody());
    }
}
