package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactControllerTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void previewReturnsMarkdownFromCurrentOrgAsSameOriginResponse() {
        ArtifactService service = mock(ArtifactService.class);
        ArtifactController controller = new ArtifactController(service, mock(RequirementDocumentService.class));
        AutoWonderContext.get().setCurrentOrgId(100L);
        byte[] bytes = "# Report".getBytes(StandardCharsets.UTF_8);
        when(service.getPreviewContent(7L, 100L))
                .thenReturn(new ArtifactService.PreviewContent("artifacts/output/report.md", bytes));

        ResponseEntity<byte[]> response = controller.preview(7L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("text/markdown;charset=UTF-8", response.getHeaders().getContentType().toString());
        assertArrayEquals(bytes, response.getBody());
        verify(service).getPreviewContent(7L, 100L);
    }

    @Test
    void previewReturnsVideoContentType() {
        ArtifactService service = mock(ArtifactService.class);
        ArtifactController controller = new ArtifactController(service, mock(RequirementDocumentService.class));
        AutoWonderContext.get().setCurrentOrgId(100L);
        byte[] bytes = new byte[] {0, 1, 2};
        when(service.getPreviewContent(7L, 100L))
                .thenReturn(new ArtifactService.PreviewContent("artifacts/output/demo.mp4", bytes));

        ResponseEntity<byte[]> response = controller.preview(7L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("video/mp4", response.getHeaders().getContentType().toString());
        assertArrayEquals(bytes, response.getBody());
    }

    @Test
    void previewReturnsNonSuccessStatusForBusinessFailures() {
        ArtifactService service = mock(ArtifactService.class);
        ArtifactController controller = new ArtifactController(service, mock(RequirementDocumentService.class));
        AutoWonderContext.get().setCurrentOrgId(100L);
        when(service.getPreviewContent(7L, 100L)).thenThrow(new BizException(ErrorCode.ARTIFACT_NOT_FOUND));

        ResponseEntity<byte[]> response = controller.preview(7L);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("text/plain", response.getHeaders().getContentType().toString());
    }
}
