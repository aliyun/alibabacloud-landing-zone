package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskDO;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskDao;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequirementDocumentServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0};

    ArtifactDao artifactDao;
    WorkitemDao workitemDao;
    ScheduledTaskDao scheduledTaskDao;
    InMemoryObjectStorage storage;
    AuditLogService auditLogService;
    RequirementDocumentService service;

    @BeforeEach
    void setUp() {
        artifactDao = mock(ArtifactDao.class);
        workitemDao = mock(WorkitemDao.class);
        scheduledTaskDao = mock(ScheduledTaskDao.class);
        storage = spy(new InMemoryObjectStorage());
        auditLogService = mock(AuditLogService.class);
        OssProperties ossProperties = new OssProperties();
        ossProperties.setArtifactBucket("artifact-bucket");
        service = new RequirementDocumentService(artifactDao, workitemDao, scheduledTaskDao,
                storage, auditLogService, ossProperties);

        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(3L);
        workitem.setTenantId(100L);
        when(workitemDao.findById(3L)).thenReturn(workitem);
        when(artifactDao.listByWorkitemAndType(100L, 3L, RequirementDocumentService.TYPE))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            ArtifactDO artifact = invocation.getArgument(0);
            artifact.setId(77L);
            return null;
        }).when(artifactDao).insert(any(ArtifactDO.class));
    }

    @Test
    void taskDocumentsUseTaskSourceAndNeverQueryWorkitemOwner() {
        ScheduledTaskDO task = scheduledTask(10001L, 20001L, "ACTIVE");
        when(scheduledTaskDao.findById(20001L, 10001L)).thenReturn(task);
        ArtifactOwnerRef owner = new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 10001L);

        service.list(owner, 20001L);

        verify(artifactDao).listBySource(20001L, "SCHEDULED_TASK", 10001L,
                RequirementDocumentService.TYPE);
        verifyNoInteractions(workitemDao);
    }

    @Test
    void sameNumericWorkitemAndTaskIdsAreQueriedWithDifferentSources() {
        ScheduledTaskDO task = scheduledTask(3L, 100L, "ACTIVE");
        when(scheduledTaskDao.findById(100L, 3L)).thenReturn(task);

        service.list(3L, 100L);
        service.list(new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 3L), 100L);

        verify(artifactDao).listByWorkitemAndType(100L, 3L, RequirementDocumentService.TYPE);
        verify(artifactDao).listBySource(100L, "SCHEDULED_TASK", 3L, RequirementDocumentService.TYPE);
    }

    @Test
    void taskUploadUsesTaskSourceAndTaskOssPath() {
        ScheduledTaskDO task = scheduledTask(5L, 100L, "ACTIVE");
        when(scheduledTaskDao.findByIdForUpdate(100L, 5L)).thenReturn(task);

        service.uploadMcp(new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 5L), "spec.md",
                "# Spec".getBytes(StandardCharsets.UTF_8), 100L, 7L, null);

        InOrder order = inOrder(scheduledTaskDao, artifactDao, storage);
        order.verify(scheduledTaskDao).findByIdForUpdate(100L, 5L);
        order.verify(artifactDao).listBySource(100L, "SCHEDULED_TASK", 5L,
                RequirementDocumentService.TYPE);
        order.verify(storage).put(eq("artifact-bucket"),
                eq("t/100/scheduled-task/5/requirements/spec.md"), any(byte[].class));
        order.verify(artifactDao).insert(any(ArtifactDO.class));
        assertEquals("# Spec", new String(storage.get(
                "artifact-bucket/t/100/scheduled-task/5/requirements/spec.md"), StandardCharsets.UTF_8));
        verify(artifactDao).insert(argThat(artifact ->
                "SCHEDULED_TASK".equals(artifact.getSourceType())
                        && artifact.getWorkitemId() == 5L));
        ArgumentCaptor<AuditLogRecord> audit = ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(auditLogService).record(audit.capture());
        assertEquals("UPLOAD_SCHEDULED_TASK_REQUIREMENT_DOC", audit.getValue().getAction());
        assertEquals("HUMAN", audit.getValue().getActorType());
        assertEquals("SCHEDULED_TASK", audit.getValue().getTargetType());
        assertEquals("SCHEDULED_TASK", audit.getValue().getDetail().get("sourceType"));
        assertEquals(5L, audit.getValue().getDetail().get("sourceId"));
        assertFalse(audit.getValue().getDetail().containsValue("# Spec"));
        verify(scheduledTaskDao, never()).findById(anyLong(), anyLong());
    }

    @Test
    void taskDeleteScopesLookupAndDeleteToTaskSource() {
        ScheduledTaskDO task = scheduledTask(3L, 100L, "ACTIVE");
        when(scheduledTaskDao.findByIdForUpdate(100L, 3L)).thenReturn(task);
        ArtifactDO artifact = requirementDocument(77L, 100L, 3L, "SCHEDULED_TASK");
        when(artifactDao.findBySourceAndId(100L, "SCHEDULED_TASK", 3L, 77L)).thenReturn(artifact);
        clearInvocations(scheduledTaskDao, artifactDao, storage);

        service.delete(new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 3L), 77L, 100L, 7L);

        InOrder order = inOrder(scheduledTaskDao, artifactDao, storage);
        order.verify(scheduledTaskDao).findByIdForUpdate(100L, 3L);
        order.verify(artifactDao).findBySourceAndId(100L, "SCHEDULED_TASK", 3L, 77L);
        order.verify(storage).delete("artifact-bucket/t/100/scheduled-task/3/requirements/spec.md");
        order.verify(artifactDao).deleteBySourceAndId(100L, "SCHEDULED_TASK", 3L, 77L);
        verify(artifactDao).deleteBySourceAndId(100L, "SCHEDULED_TASK", 3L, 77L);
        verify(artifactDao, never()).deleteById(anyLong(), anyLong());
        verify(scheduledTaskDao, never()).findById(anyLong(), anyLong());
    }

    @Test
    void archivedTaskAllowsListing() {
        ScheduledTaskDO task = scheduledTask(3L, 100L, "ARCHIVED");
        when(scheduledTaskDao.findById(100L, 3L)).thenReturn(task);
        ArtifactOwnerRef owner = new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 3L);

        service.list(owner, 100L);

        verify(artifactDao).listBySource(100L, "SCHEDULED_TASK", 3L, RequirementDocumentService.TYPE);
        verify(scheduledTaskDao, never()).findByIdForUpdate(anyLong(), anyLong());
    }

    @Test
    void archivedTaskMutationUsesLockedReadAndTouchesNeitherArtifactsNorStorage() {
        ScheduledTaskDO task = scheduledTask(3L, 100L, "ARCHIVED");
        when(scheduledTaskDao.findByIdForUpdate(100L, 3L)).thenReturn(task);
        ArtifactOwnerRef owner = new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 3L);

        BizException ex = assertThrows(BizException.class, () -> service.uploadMcp(owner, "spec.md",
                "# Spec".getBytes(StandardCharsets.UTF_8), 100L, 7L, null));

        assertEquals("30005", ex.getCode());
        verify(scheduledTaskDao).findByIdForUpdate(100L, 3L);
        verify(scheduledTaskDao, never()).findById(anyLong(), anyLong());
        verifyNoInteractions(artifactDao, storage);
    }

    @Test
    void sameOwnerSecondUploadRereadsAfterLockAndConflictsBeforeOverwritingObject() {
        ScheduledTaskDO task = scheduledTask(5L, 100L, "ACTIVE");
        when(scheduledTaskDao.findByIdForUpdate(100L, 5L)).thenReturn(task);
        ArtifactDO existing = requirementDocument(77L, 100L, 5L, "SCHEDULED_TASK");
        existing.setOssRef("artifact-bucket/t/100/scheduled-task/5/requirements/spec.md");
        when(artifactDao.listBySource(100L, "SCHEDULED_TASK", 5L, RequirementDocumentService.TYPE))
                .thenReturn(List.of(), List.of(existing));
        ArtifactOwnerRef owner = new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 5L);

        service.uploadMcp(owner, "spec.md", "# First".getBytes(StandardCharsets.UTF_8),
                100L, 7L, null);
        BizException ex = assertThrows(BizException.class, () -> service.uploadMcp(owner, "spec.md",
                "# Second".getBytes(StandardCharsets.UTF_8), 100L, 7L, null));

        assertEquals("10409", ex.getCode());
        assertEquals("# First", new String(storage.get(
                "artifact-bucket/t/100/scheduled-task/5/requirements/spec.md"), StandardCharsets.UTF_8));
        verify(scheduledTaskDao, times(2)).findByIdForUpdate(100L, 5L);
        verify(artifactDao, times(2)).listBySource(100L, "SCHEDULED_TASK", 5L,
                RequirementDocumentService.TYPE);
        verify(artifactDao, times(1)).insert(any(ArtifactDO.class));
    }

    @Test
    void ownerMutationProxyEntriesAreTransactional() throws Exception {
        assertNotNull(RequirementDocumentService.class.getMethod("uploadWeb",
                        ArtifactOwnerRef.class, MultipartFile[].class, long.class, long.class)
                .getAnnotation(Transactional.class));
        assertNotNull(RequirementDocumentService.class.getMethod("uploadMcp",
                        ArtifactOwnerRef.class, String.class, byte[].class, long.class, long.class, String.class)
                .getAnnotation(Transactional.class));
        assertNotNull(RequirementDocumentService.class.getMethod("delete",
                        ArtifactOwnerRef.class, long.class, long.class, long.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void missingOrCrossTenantTaskIsRejected() {
        ArtifactOwnerRef owner = new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 3L);

        BizException ex = assertThrows(BizException.class, () -> service.list(owner, 100L));

        assertEquals("30001", ex.getCode());
        verify(artifactDao, never()).listBySource(anyLong(), any(), anyLong(), any());
    }

    @Test
    void taskRunOwnerIsRejectedForRequirementDocuments() {
        ArtifactOwnerRef owner = new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK_RUN, 3L);

        BizException ex = assertThrows(BizException.class, () -> service.list(owner, 100L));

        assertEquals("10001", ex.getCode());
        verifyNoInteractions(workitemDao, scheduledTaskDao, artifactDao);
    }

    private ScheduledTaskDO scheduledTask(long id, long tenantId, String status) {
        ScheduledTaskDO task = new ScheduledTaskDO();
        task.setId(id);
        task.setWorkspaceId(tenantId);
        task.setStatus(status);
        return task;
    }

    private ArtifactDO requirementDocument(long id, long tenantId, long sourceId, String sourceType) {
        ArtifactDO artifact = new ArtifactDO();
        artifact.setId(id);
        artifact.setTenantId(tenantId);
        artifact.setSourceType(sourceType);
        artifact.setWorkitemId(sourceId);
        artifact.setName("requirements/spec.md");
        artifact.setType(RequirementDocumentService.TYPE);
        artifact.setOssRef("artifact-bucket/t/100/scheduled-task/3/requirements/spec.md");
        artifact.setSize(6L);
        storage.put("artifact-bucket", "t/100/scheduled-task/3/requirements/spec.md",
                "# Spec".getBytes(StandardCharsets.UTF_8));
        return artifact;
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
        ArgumentCaptor<AuditLogRecord> audit = ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(auditLogService).record(audit.capture());
        assertEquals("USER", audit.getValue().getActorType());
        assertEquals("workitem", audit.getValue().getTargetType());
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
        when(artifactDao.listByWorkitemAndType(100L, 3L, RequirementDocumentService.TYPE))
                .thenReturn(List.of(existing));

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
    void uploadRejectsUnsafeOrUnsupportedFilenames() {
        assertThrows(BizException.class, () -> service.uploadMcp(3L, "../spec.md",
                "# Spec".getBytes(StandardCharsets.UTF_8), 100L, 7L, null));
        assertThrows(BizException.class, () -> service.uploadMcp(3L, "archive.zip",
                "# Spec".getBytes(StandardCharsets.UTF_8), 100L, 7L, null));
        verify(artifactDao, never()).insert(any());
    }

    @Test
    void uploadMcpStoresPlainTextContextWithDetectedContentType() {
        service.uploadMcp(3L, "notes.txt", "plain notes".getBytes(StandardCharsets.UTF_8), 100L, 7L, null);

        assertArrayEquals("plain notes".getBytes(StandardCharsets.UTF_8),
                storage.get("artifact-bucket/t/100/workitem/3/requirements/notes.txt"));
        verify(artifactDao).insert(argThat(artifact ->
                artifact.getMetaJson().contains("\"contentType\":\"text/plain\"")
                        && artifact.getMetaJson().contains("\"contextKind\":\"TEXT\"")));
    }

    @Test
    void uploadMcpStoresHtmlContextWithDetectedContentType() {
        byte[] html = "<html><body>PRD</body></html>".getBytes(StandardCharsets.UTF_8);
        service.uploadMcp(3L, "prd.html", html, 100L, 7L, null);

        assertArrayEquals(html, storage.get("artifact-bucket/t/100/workitem/3/requirements/prd.html"));
        verify(artifactDao).insert(argThat(artifact ->
                artifact.getMetaJson().contains("\"contentType\":\"text/html\"")
                        && artifact.getMetaJson().contains("\"contextKind\":\"TEXT\"")));
    }

    @Test
    void uploadMcpStoresPdfContextWithDetectedContentType() {
        byte[] pdf = "%PDF-1.4 minimal".getBytes(StandardCharsets.UTF_8);
        service.uploadMcp(3L, "spec.pdf", pdf, 100L, 7L, null);

        assertArrayEquals(pdf, storage.get("artifact-bucket/t/100/workitem/3/requirements/spec.pdf"));
        verify(artifactDao).insert(argThat(artifact ->
                artifact.getMetaJson().contains("\"contentType\":\"application/pdf\"")
                        && artifact.getMetaJson().contains("\"contextKind\":\"PDF\"")));
    }

    @Test
    void uploadRejectsPdfWithMismatchedMagicBytes() {
        assertThrows(BizException.class, () -> service.uploadMcp(3L, "spec.pdf",
                "not-a-pdf".getBytes(StandardCharsets.UTF_8), 100L, 7L, null));
        assertThrows(BizException.class, () -> service.uploadMcp(3L, "spec.pdf",
                new byte[]{'%', 'P', 'D'}, 100L, 7L, null));

        verify(artifactDao, never()).insert(any());
    }

    @Test
    void uploadRejectsTextDocumentWithInvalidUtf8() {
        assertThrows(BizException.class, () -> service.uploadMcp(3L, "notes.txt",
                new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0x00}, 100L, 7L, null));

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
        when(artifactDao.listByWorkitemAndType(100L, 3L, RequirementDocumentService.TYPE))
                .thenReturn(existing);

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
        when(artifactDao.findWorkitemByTenantAndId(100L, 77L)).thenReturn(artifact);

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
        when(artifactDao.findWorkitemByTenantAndId(100L, 77L)).thenReturn(artifact);

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
