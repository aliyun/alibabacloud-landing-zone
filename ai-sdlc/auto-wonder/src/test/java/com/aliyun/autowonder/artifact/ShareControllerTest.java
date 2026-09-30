package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.workitem.WorkitemDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShareControllerTest {
    @Test
    void browserLinksOpenPreviewButDownloadAndRawReadsRemainUnchanged() throws Exception {
        when(shareService.findWorkitemByShareToken(TOKEN)).thenReturn(workitem());
        ArtifactDO artifact = artifact(301L, "report.html", 6L);
        when(shareService.findExposedArtifact(TENANT_ID, WORKITEM_ID, 301L)).thenReturn(artifact);
        when(shareService.loadContent(artifact)).thenReturn("<html>".getBytes());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        String url = "/api/share/workitems/" + TOKEN + "/artifacts/301";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).accept(MediaType.TEXT_HTML))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location", url + "?preview"));
        verify(shareService, never()).loadContent(any());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).queryParam("download", "true").accept(MediaType.TEXT_HTML))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes("<html>".getBytes()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", "application/octet-stream"));
    }

    @Test
    void previewShellStaysOnThePublicCapabilityEndpointAndRejectsPrivateFiles() {
        when(shareService.findWorkitemByShareToken(TOKEN)).thenReturn(workitem());
        var receipt = new ArtifactShareRequestDO(); receipt.setStatus("PENDING");
        when(requests.findById(TENANT_ID, WORKITEM_ID, 41L)).thenReturn(receipt);
        var page = controller.preview(TOKEN, "requests", 41L);
        assertEquals(200, page.getStatusCodeValue());
        assertEquals(MediaType.TEXT_HTML, page.getHeaders().getContentType());
        assertEquals("index.html", page.getBody().getFilename());
        assertEquals("no-referrer", page.getHeaders().getFirst("Referrer-Policy"));
        assertEquals("no-store", page.getHeaders().getCacheControl());
        receipt.setStatus("REJECTED");
        assertEquals(404, controller.preview(TOKEN, "requests", 41L).getStatusCodeValue());
        assertEquals(404, controller.preview(TOKEN, "artifacts", 301L).getStatusCodeValue());
        assertEquals(404, controller.preview("invalid", "requests", 41L).getStatusCodeValue());
        verify(shareService, never()).loadContent(any());
    }

    @Test
    void metadataPollsReservedRequestAndOnlyExposesPublishedFileMetadata() throws Exception {
        when(shareService.findWorkitemByShareToken(TOKEN)).thenReturn(workitem());
        var receipt = new ArtifactShareRequestDO();
        receipt.setStatus("PENDING"); receipt.setName("deliverables/report.md");
        when(requests.findById(TENANT_ID, WORKITEM_ID, 41L)).thenReturn(receipt);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        String url = "/api/share/workitems/" + TOKEN + "/requests/41";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).queryParam("metadata", "").accept(MediaType.APPLICATION_JSON))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("PENDING"));
        receipt.setStatus("SHARED"); receipt.setArtifactId(301L);
        when(shareService.findExposedArtifact(TENANT_ID, WORKITEM_ID, 301L)).thenReturn(artifact(301L, "report.md", 42L));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).queryParam("metadata", "").accept(MediaType.APPLICATION_JSON))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("SHARED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.name").value("report.md"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.ossRef").doesNotExist());
        receipt.setStatus("REJECTED");
        assertEquals(404, controller.metadata(TOKEN, "requests", 41L).getStatusCode().value());
        assertEquals(404, controller.metadata(TOKEN, "requests", 99L).getStatusCode().value());
        assertEquals(404, controller.metadata(TOKEN, "artifacts", 99L).getStatusCode().value());
        assertEquals(404, controller.metadata("unknown", "artifacts", 301L).getStatusCode().value());
        verify(shareService, never()).loadContent(any());
    }

    private static final String TOKEN = "awshare_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0UvW";
    private static final long TENANT_ID = 100L;
    private static final long WORKITEM_ID = 16904L;

    private ExternalArtifactShareService shareService;
    private ShareController controller;
    private ArtifactShareRequestDao requests;

    @BeforeEach
    void setUp() {
        shareService = mock(ExternalArtifactShareService.class);
        requests = mock(ArtifactShareRequestDao.class);
        controller = new ShareController(shareService, requests);
    }

    @Test
    void reservedUrlServesNoBytesUntilVerifiedAndRetainsDownloadHeaders() {
        when(shareService.findWorkitemByShareToken(TOKEN)).thenReturn(workitem());
        ArtifactShareRequestDO receipt = new ArtifactShareRequestDO();
        receipt.setStatus("PENDING");
        when(requests.findById(TENANT_ID, WORKITEM_ID, 41L)).thenReturn(receipt);
        var pending = controller.request(TOKEN, 41L, false, "");
        assertEquals(202, pending.getStatusCode().value());
        assertEquals("no-store", pending.getHeaders().getCacheControl());
        assertTrue(new String(pending.getBody(), java.nio.charset.StandardCharsets.UTF_8).contains("产物正在生成"));
        verify(shareService, never()).loadContent(any());

        ArtifactDO artifact = artifact(301L, "report.html", 6L);
        when(shareService.findExposedArtifact(TENANT_ID, WORKITEM_ID, 301L)).thenReturn(artifact);
        when(shareService.loadContent(artifact)).thenReturn("<html>".getBytes());
        receipt.setStatus("SHARED"); receipt.setArtifactId(301L);
        var ready = controller.request(TOKEN, 41L, true, "");
        assertEquals(200, ready.getStatusCode().value());
        assertArrayEquals("<html>".getBytes(), ready.getBody());
        assertEquals("nosniff", ready.getHeaders().getFirst("X-Content-Type-Options"));
        assertTrue(ready.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"));
    }

    @Test
    void reservedUrlRejectsInvalidTokenForeignRequestAndRejectedReceipt() {
        assertEquals(404, controller.request("unknown", 41L, false, "").getStatusCode().value());
        verifyNoInteractions(requests);
        when(shareService.findWorkitemByShareToken(TOKEN)).thenReturn(workitem());
        assertEquals(404, controller.request(TOKEN, 41L, false, "").getStatusCode().value());
        verify(requests).findById(TENANT_ID, WORKITEM_ID, 41L);
        var receipt = new ArtifactShareRequestDO(); receipt.setStatus("REJECTED"); receipt.setArtifactId(301L);
        when(requests.findById(TENANT_ID, WORKITEM_ID, 41L)).thenReturn(receipt);
        assertEquals(404, controller.request(TOKEN, 41L, false, "").getStatusCode().value());
        verify(shareService, never()).loadContent(any());
    }

    private WorkitemDO workitem() {
        WorkitemDO w = new WorkitemDO();
        w.setId(WORKITEM_ID);
        w.setTenantId(TENANT_ID);
        w.setTitle("工单 <标题> & 测试");
        return w;
    }

    private ArtifactDO artifact(long id, String name, long size) {
        ArtifactDO a = new ArtifactDO();
        a.setId(id);
        a.setTenantId(TENANT_ID);
        a.setWorkitemId(WORKITEM_ID);
        a.setName(name);
        a.setType("DELIVERABLE");
        a.setOssRef("oss://bucket/" + id);
        a.setSize(size);
        a.setGmtCreate(new Date(0));
        return a;
    }

    @Test
    void directoryListsExposedArtifactsWithEscapedTitleAndNames() {
        when(shareService.findWorkitemByShareToken(TOKEN)).thenReturn(workitem());
        when(shareService.listExposed(TENANT_ID, WORKITEM_ID)).thenReturn(List.of(
                artifact(301L, "artifacts/output/deliverables/closure-report.md", 2048L)));

        ResponseEntity<String> resp = controller.directory(TOKEN);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(MediaType.valueOf("text/html;charset=UTF-8"), resp.getHeaders().getContentType());
        assertEquals("no-store", resp.getHeaders().getCacheControl());
        String html = resp.getBody();
        // 标题与文件名均转义，防注入；链接只含 token 与 artifactId
        assertTrue(html.contains("工单 &lt;标题&gt; &amp; 测试"));
        assertTrue(html.contains("deliverables/closure-report.md"));
        assertTrue(html.contains("href=\"/api/share/workitems/" + TOKEN + "/artifacts/301\""));
        assertFalse(html.contains("artifacts/output/"));
        assertTrue(html.contains("2.0 KB"));
    }

    @Test
    void directoryReturns404ForUnknownOrMissingToken() {
        when(shareService.findWorkitemByShareToken("awshare_unknown")).thenReturn(null);
        assertEquals(404, controller.directory("awshare_unknown").getStatusCode().value());
    }

    @Test
    void artifactServesInlineMarkdownWithNosniff() {
        when(shareService.findWorkitemByShareToken(TOKEN)).thenReturn(workitem());
        ArtifactDO a = artifact(301L, "artifacts/output/deliverables/closure-report.md", 6L);
        when(shareService.findExposedArtifact(TENANT_ID, WORKITEM_ID, 301L)).thenReturn(a);
        when(shareService.loadContent(a)).thenReturn("# 结论".getBytes());

        ResponseEntity<byte[]> resp = controller.artifact(TOKEN, 301L, false, "");

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(MediaType.valueOf("text/markdown;charset=UTF-8"), resp.getHeaders().getContentType());
        assertEquals("nosniff", resp.getHeaders().getFirst("X-Content-Type-Options"));
        assertTrue(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("inline"));
        assertEquals("# 结论", new String(resp.getBody()));
    }

    @Test
    void artifactForcesAttachmentForDownloadParamAndUnsafeTypes() {
        when(shareService.findWorkitemByShareToken(TOKEN)).thenReturn(workitem());
        ArtifactDO a = artifact(301L, "deliverables/closure-report.md", 6L);
        when(shareService.findExposedArtifact(TENANT_ID, WORKITEM_ID, 301L)).thenReturn(a);
        when(shareService.loadContent(a)).thenReturn("x".getBytes());

        ResponseEntity<byte[]> forced = controller.artifact(TOKEN, 301L, true, "");
        assertTrue(forced.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)
                .contains("attachment; filename*=UTF-8''deliverables_closure-report.md"));

        // html 一律附件下载，不内联渲染
        ArtifactDO htmlArtifact = artifact(302L, "evidence/report.html", 6L);
        when(shareService.findExposedArtifact(TENANT_ID, WORKITEM_ID, 302L)).thenReturn(htmlArtifact);
        when(shareService.loadContent(htmlArtifact)).thenReturn("<script>".getBytes());
        ResponseEntity<byte[]> html = controller.artifact(TOKEN, 302L, false, "");
        assertTrue(html.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"));
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, html.getHeaders().getContentType());
    }

    @Test
    void artifactReturns404ForUnknownTokenOrUnexposedArtifact() {
        when(shareService.findWorkitemByShareToken("awshare_unknown")).thenReturn(null);
        assertEquals(404, controller.artifact("awshare_unknown", 1L, false, "").getStatusCode().value());

        when(shareService.findWorkitemByShareToken(TOKEN)).thenReturn(workitem());
        when(shareService.findExposedArtifact(TENANT_ID, WORKITEM_ID, 999L)).thenReturn(null);
        assertEquals(404, controller.artifact(TOKEN, 999L, false, "").getStatusCode().value());
        verify(shareService, never()).loadContent(any());
    }
}
