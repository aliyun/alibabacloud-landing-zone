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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactControllerTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void previewReturnsMarkdownFromCurrentWorkspaceAsSameOriginResponse() {
        ArtifactService service = mock(ArtifactService.class);
        ArtifactController controller = new ArtifactController(service, mock(RequirementDocumentService.class));
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
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
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        byte[] bytes = new byte[] {0, 1, 2};
        when(service.getPreviewContent(7L, 100L))
                .thenReturn(new ArtifactService.PreviewContent("artifacts/output/demo.mp4", bytes));

        ResponseEntity<byte[]> response = controller.preview(7L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("video/mp4", response.getHeaders().getContentType().toString());
        assertArrayEquals(bytes, response.getBody());
    }

    @Test
    void previewReturnsHtmlWithSandboxCspHeader() {
        ArtifactService service = mock(ArtifactService.class);
        ArtifactController controller = new ArtifactController(service, mock(RequirementDocumentService.class));
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        byte[] bytes = "<html><body>plan</body></html>".getBytes(StandardCharsets.UTF_8);
        when(service.getPreviewContent(7L, 100L))
                .thenReturn(new ArtifactService.PreviewContent("requirements/plan.html", bytes));

        ResponseEntity<byte[]> response = controller.preview(7L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("text/html;charset=UTF-8", response.getHeaders().getContentType().toString());
        assertEquals("sandbox", response.getHeaders().getFirst("Content-Security-Policy"));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertArrayEquals(bytes, response.getBody());
    }

    @Test
    void previewReturnsHtmlCspForHtmAndUppercaseExtensions() {
        ArtifactService service = mock(ArtifactService.class);
        ArtifactController controller = new ArtifactController(service, mock(RequirementDocumentService.class));
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        byte[] bytes = "<html></html>".getBytes(StandardCharsets.UTF_8);
        when(service.getPreviewContent(7L, 100L))
                .thenReturn(new ArtifactService.PreviewContent("requirements/PROTOTYPE.HTM", bytes));

        ResponseEntity<byte[]> htm = controller.preview(7L);
        assertEquals("text/html;charset=UTF-8", htm.getHeaders().getContentType().toString());
        assertEquals("sandbox", htm.getHeaders().getFirst("Content-Security-Policy"));

        when(service.getPreviewContent(7L, 100L))
                .thenReturn(new ArtifactService.PreviewContent("requirements/PLAN.HTML", bytes));

        ResponseEntity<byte[]> upper = controller.preview(7L);
        assertEquals("text/html;charset=UTF-8", upper.getHeaders().getContentType().toString());
        assertEquals("sandbox", upper.getHeaders().getFirst("Content-Security-Policy"));
    }

    @Test
    void previewDoesNotAddSandboxCspToNonHtmlArtifacts() {
        ArtifactService service = mock(ArtifactService.class);
        ArtifactController controller = new ArtifactController(service, mock(RequirementDocumentService.class));
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        when(service.getPreviewContent(7L, 100L))
                .thenReturn(new ArtifactService.PreviewContent("artifacts/output/report.md",
                        "# Report".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<byte[]> response = controller.preview(7L);

        assertEquals("text/markdown;charset=UTF-8", response.getHeaders().getContentType().toString());
        assertNull(response.getHeaders().getFirst("Content-Security-Policy"));
    }

    @Test
    void previewReturnsNonSuccessStatusForBusinessFailures() {
        ArtifactService service = mock(ArtifactService.class);
        ArtifactController controller = new ArtifactController(service, mock(RequirementDocumentService.class));
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        when(service.getPreviewContent(7L, 100L)).thenThrow(new BizException(ErrorCode.ARTIFACT_NOT_FOUND));

        ResponseEntity<byte[]> response = controller.preview(7L);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("text/plain", response.getHeaders().getContentType().toString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"zh-CN", "en-US"})
    void previewLocalizesOnlyExplicitMetadataWithSamePlainResponse(String language) {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag(language));
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        try {
            var service=mock(ArtifactService.class);
            var controller=new ArtifactController(service, mock(RequirementDocumentService.class));
            BizException[] errors={new BizException(ErrorCode.ARTIFACT_NOT_FOUND),
                new BizException(ErrorCode.UNAUTHORIZED),
                BizException.keyed(ErrorCode.PARAM_INVALID, "executor.launch.version_required", new Object[]{"<raw>"}, "raw diagnostic"),
                new BizException(ErrorCode.ARTIFACT_NOT_FOUND, "产物不存在"),
                BizException.keyed("unknown", "missing.key", null, "<raw>产物不存在", null)};
            boolean en=language.equals("en-US");
            String[] expected={en ? "Artifact not found" : "产物不存在", en ? "Not signed in or session expired" : "未登录或登录已失效",
                en ? "version is required. Read it from <raw>." : "version 必填，取自 <raw>", "产物不存在", "<raw>产物不存在"};
            int[] statuses={404,401,400,404,400};
            for(int i=0;i<errors.length;i++) {
                org.mockito.Mockito.doThrow(errors[i]).when(service).getPreviewContent(7L,100L);
                var response=controller.preview(7L);
                assertEquals(statuses[i],response.getStatusCode().value());
                assertEquals("text/plain",response.getHeaders().getContentType().toString());
                assertEquals("nosniff",response.getHeaders().getFirst("X-Content-Type-Options"));
                assertArrayEquals(expected[i].getBytes(StandardCharsets.UTF_8),response.getBody());
            }
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }

}
