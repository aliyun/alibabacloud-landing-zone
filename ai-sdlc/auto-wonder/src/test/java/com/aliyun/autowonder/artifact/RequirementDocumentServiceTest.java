package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequirementDocumentServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0};

    ArtifactDao artifactDao;
    WorkitemDao workitemDao;
    InMemoryObjectStorage storage;
    AuditLogService auditLogService;
    RequirementDocumentService service;

    @BeforeEach
    void setUp() {
        artifactDao = mock(ArtifactDao.class);
        workitemDao = mock(WorkitemDao.class);
        storage = new InMemoryObjectStorage();
        auditLogService = mock(AuditLogService.class);
        OssProperties ossProperties = new OssProperties();
        ossProperties.setArtifactBucket("artifact-bucket");
        service = new RequirementDocumentService(artifactDao, workitemDao, storage, auditLogService, ossProperties);

        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(3L);
        workitem.setTenantId(100L);
        when(workitemDao.findById(3L)).thenReturn(workitem);
        when(artifactDao.listByWorkitemAndType(100L, 3L, RequirementDocumentService.TYPE)).thenReturn(List.of());
        doAnswer(invocation -> {
            ArtifactDO artifact = invocation.getArgument(0);
            artifact.setId(77L);
            return null;
        }).when(artifactDao).insert(any(ArtifactDO.class));
    }

    @Test
    void uploadMcpStoresMarkdownAsRequirementDocArtifact() {
        var vo = service.uploadMcp(3L, "spec.md", "# Spec".getBytes(StandardCharsets.UTF_8),
                100L, 7L, "/tmp/spec.md");

        assertEquals(77L, vo.getId());
        assertEquals("requirements/spec.md", vo.getName());
        assertEquals(RequirementDocumentService.TYPE, vo.getType());
        assertEquals("# Spec", new String(storage.get("artifact-bucket/t/100/workitem/3/requirements/spec.md"),
                StandardCharsets.UTF_8));
        verify(artifactDao).insert(org.mockito.ArgumentMatchers.argThat(artifact ->
                artifact.getDispatchId() == null
                        && "requirements/spec.md".equals(artifact.getName())
                        && RequirementDocumentService.TYPE.equals(artifact.getType())
                        && artifact.getMetaJson().contains("\"source\":\"MCP\"")));
        verify(auditLogService).record(any());
    }

    @Test
    void uploadCliStoresFilesInInputOrderWithCliAuditSource() throws Exception {
        var vos = service.uploadCli(3L, new MultipartFile[]{
                new MockMultipartFile("files", "a.md", "text/markdown",
                        "# A".getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("files", "b.png", "image/png", PNG)}, 100L, 7L);

        assertEquals(List.of("requirements/a.md", "requirements/b.png"),
                vos.stream().map(vo -> vo.getName()).collect(java.util.stream.Collectors.toList()));
        assertEquals("# A", new String(storage.get("artifact-bucket/t/100/workitem/3/requirements/a.md"),
                StandardCharsets.UTF_8));
        verify(artifactDao, times(2)).insert(argThat(artifact ->
                artifact.getMetaJson().contains("\"source\":\"CLI\"")));
        verify(auditLogService, times(2)).record(argThat(record ->
                "CLI".equals(record.getTriggerSource())));
    }

    @Test
    void uploadRejectsDuplicateRequirementDocumentName() {
        ArtifactDO existing = new ArtifactDO();
        existing.setName("requirements/spec.md");
        existing.setSize(10L);
        when(artifactDao.listByWorkitemAndType(100L, 3L, RequirementDocumentService.TYPE)).thenReturn(List.of(existing));

        BizException ex = assertThrows(BizException.class, () -> service.uploadMcp(3L, "spec.md",
                "# Spec".getBytes(StandardCharsets.UTF_8), 100L, 7L, null));

        assertEquals("10409", ex.getCode());
        verify(artifactDao, never()).insert(any());
    }

    @Test
    void replaceClarificationDocumentDeletesThePreviousClarificationBeforeStoringTheNewOne() {
        ArtifactDO old = new ArtifactDO();
        old.setId(66L);
        old.setTenantId(100L);
        old.setWorkitemId(3L);
        old.setName("requirements/clarification.md");
        old.setType(RequirementDocumentService.TYPE);
        old.setOssRef("artifact-bucket/t/100/workitem/3/requirements/clarification.md");
        old.setSize(3L);
        storage.put("artifact-bucket", "t/100/workitem/3/requirements/clarification.md",
                "old".getBytes(StandardCharsets.UTF_8));
        when(artifactDao.listByWorkitemAndType(100L, 3L, RequirementDocumentService.TYPE))
                .thenReturn(List.of(old), List.of());

        service.replaceClarificationDocument(3L, "# New clarification", 100L, 7L);

        verify(artifactDao).deleteById(100L, 66L);
        verify(artifactDao).insert(argThat(artifact ->
                "requirements/clarification.md".equals(artifact.getName())));
        assertEquals("# New clarification", new String(storage.get(
                "artifact-bucket/t/100/workitem/3/requirements/clarification.md"), StandardCharsets.UTF_8));
    }

    @Test
    void uploadRejectsUnsafeOrNonMarkdownFilenames() {
        assertThrows(BizException.class, () -> service.uploadMcp(3L, "../spec.md",
                "# Spec".getBytes(StandardCharsets.UTF_8), 100L, 7L, null));
        assertThrows(BizException.class, () -> service.uploadMcp(3L, "spec.txt",
                "# Spec".getBytes(StandardCharsets.UTF_8), 100L, 7L, null));
        verify(artifactDao, never()).insert(any());
    }

    @Test
    void uploadEnforcesDocumentCountLimit() {
        List<ArtifactDO> existing = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> {
                    ArtifactDO artifact = new ArtifactDO();
                    artifact.setName("requirements/doc" + i + ".md");
                    artifact.setSize(1L);
                    return artifact;
                }).toList();
        when(artifactDao.listByWorkitemAndType(100L, 3L, RequirementDocumentService.TYPE)).thenReturn(existing);

        BizException ex = assertThrows(BizException.class, () -> service.uploadMcp(3L, "extra.md",
                "# Extra".getBytes(StandardCharsets.UTF_8), 100L, 7L, null));

        assertEquals("10001", ex.getCode());
        verify(artifactDao, never()).insert(any());
    }

    @Test
    void deleteRemovesArtifactRowAndObjectWhenScopedToWorkitem() {
        ArtifactDO artifact = new ArtifactDO();
        artifact.setId(77L);
        artifact.setTenantId(100L);
        artifact.setWorkitemId(3L);
        artifact.setName("requirements/spec.md");
        artifact.setType(RequirementDocumentService.TYPE);
        artifact.setOssRef("artifact-bucket/t/100/workitem/3/requirements/spec.md");
        artifact.setSize(6L);
        storage.put("artifact-bucket", "t/100/workitem/3/requirements/spec.md", "# Spec".getBytes(StandardCharsets.UTF_8));
        when(artifactDao.findById(77L)).thenReturn(artifact);

        service.delete(3L, 77L, 100L, 7L);

        assertTrue(!storage.exists("artifact-bucket/t/100/workitem/3/requirements/spec.md"));
        verify(artifactDao).deleteById(100L, 77L);
        verify(auditLogService).record(any());
    }

    @Test
    void deleteRejectsArtifactFromAnotherWorkitem() {
        ArtifactDO artifact = new ArtifactDO();
        artifact.setId(77L);
        artifact.setTenantId(100L);
        artifact.setWorkitemId(4L);
        artifact.setType(RequirementDocumentService.TYPE);
        when(artifactDao.findById(77L)).thenReturn(artifact);

        BizException ex = assertThrows(BizException.class, () -> service.delete(3L, 77L, 100L, 7L));

        assertEquals("17010", ex.getCode());
        verify(artifactDao, never()).deleteById(anyLong(), anyLong());
    }

    @Test
    void uploadMcpStoresVisualContextWithDetectedContentType() {
        service.uploadMcp(3L, "screen.png", PNG, 100L, 7L, "/tmp/screen.png");

        assertArrayEquals(PNG, storage.get("artifact-bucket/t/100/workitem/3/requirements/screen.png"));
        verify(artifactDao).insert(argThat(artifact ->
                artifact.getMetaJson().contains("\"contentType\":\"image/png\"")
                        && artifact.getMetaJson().contains("\"contextKind\":\"VISUAL\"")));
    }

    @Test
    void uploadMcpStoresJpegVisualContextWithDetectedContentType() {
        service.uploadMcp(3L, "shot.jpeg", JPEG, 100L, 7L, null);

        assertArrayEquals(JPEG, storage.get("artifact-bucket/t/100/workitem/3/requirements/shot.jpeg"));
        verify(artifactDao).insert(argThat(artifact ->
                artifact.getMetaJson().contains("\"contentType\":\"image/jpeg\"")
                        && artifact.getMetaJson().contains("\"contextKind\":\"VISUAL\"")));
    }

    @Test
    void uploadMcpStoresWebpVisualContextWithDetectedContentType() {
        service.uploadMcp(3L, "flow.webp", WEBP, 100L, 7L, null);

        assertArrayEquals(WEBP, storage.get("artifact-bucket/t/100/workitem/3/requirements/flow.webp"));
        verify(artifactDao).insert(argThat(artifact ->
                artifact.getMetaJson().contains("\"contentType\":\"image/webp\"")
                        && artifact.getMetaJson().contains("\"contextKind\":\"VISUAL\"")));
    }

    @Test
    void uploadRejectsImageWithMismatchedMagicBytes() {
        assertThrows(BizException.class, () -> service.uploadMcp(3L, "screen.png",
                "not-an-image".getBytes(StandardCharsets.UTF_8), 100L, 7L, null));
        assertThrows(BizException.class, () -> service.uploadMcp(3L, "shot.jpg", PNG, 100L, 7L, null));
        assertThrows(BizException.class, () -> service.uploadMcp(3L, "flow.webp",
                "RIFFxxxxAVI ".getBytes(StandardCharsets.UTF_8), 100L, 7L, null));

        verify(artifactDao, never()).insert(any());
    }

    @Test
    void uploadRejectsUnsupportedVisualExtensions() {
        assertThrows(BizException.class, () -> service.uploadMcp(3L, "anim.gif", PNG, 100L, 7L, null));
        assertThrows(BizException.class, () -> service.uploadMcp(3L, "vector.svg", PNG, 100L, 7L, null));

        verify(artifactDao, never()).insert(any());
    }

    @Test
    void uploadRejectsImageAbovePerFileLimit() {
        byte[] oversized = pngWithSize(5 * 1024 * 1024 + 1);

        assertThrows(BizException.class, () -> service.uploadMcp(3L, "big.png", oversized, 100L, 7L, null));

        verify(artifactDao, never()).insert(any());
    }

    @Test
    void uploadEnforcesAggregateSizeLimitAtTwentyMebibytes() {
        ArtifactDO existing = new ArtifactDO();
        existing.setName("requirements/existing.md");
        existing.setSize(15L * 1024L * 1024L);
        when(artifactDao.listByWorkitemAndType(100L, 3L, RequirementDocumentService.TYPE))
                .thenReturn(List.of(existing));

        service.uploadMcp(3L, "screen.png", pngWithSize(5 * 1024 * 1024), 100L, 7L, null);

        ArtifactDO larger = new ArtifactDO();
        larger.setName("requirements/existing.md");
        larger.setSize(16L * 1024L * 1024L);
        when(artifactDao.listByWorkitemAndType(100L, 3L, RequirementDocumentService.TYPE))
                .thenReturn(List.of(larger));

        BizException ex = assertThrows(BizException.class, () -> service.uploadMcp(3L, "screen2.png",
                pngWithSize(5 * 1024 * 1024), 100L, 7L, null));
        assertEquals("10001", ex.getCode());
    }

    private static byte[] pngWithSize(int size) {
        byte[] bytes = new byte[size];
        System.arraycopy(PNG, 0, bytes, 0, PNG.length);
        return bytes;
    }
}
