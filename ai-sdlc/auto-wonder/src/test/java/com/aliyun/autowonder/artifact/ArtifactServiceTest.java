package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.artifact.dto.ArtifactVO;
import com.aliyun.autowonder.artifact.dto.ReportArtifactRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArtifactServiceTest {

    ArtifactDao artifactDao;
    ObjectStorage storage;
    ArtifactService service;

    @BeforeEach
    void setUp() {
        artifactDao = mock(ArtifactDao.class);
        storage = mock(ObjectStorage.class);
        service = new ArtifactService(artifactDao, storage);
    }

    @Test
    void record_persists_and_returns_id() {
        doAnswer(inv -> { ((ArtifactDO) inv.getArgument(0)).setId(1L); return null; })
                .when(artifactDao).insert(any());

        ReportArtifactRequest req = new ReportArtifactRequest();
        req.setWorkitemId(3L);
        req.setDispatchId(8L);
        req.setName("out.patch");
        req.setType("PATCH");
        req.setOssRef("autowonder-artifacts-daily/3/out.patch");
        req.setSize(120L);

        Long id = service.record(req, 100L);

        assertNotNull(id);
        verify(artifactDao).insert(argThat((ArtifactDO a) ->
                a.getTenantId() == 100L && a.getWorkitemId() == 3L
                        && "WORKITEM".equals(a.getSourceType())
                        && "PATCH".equals(a.getType())
                        && "autowonder-artifacts-daily/3/out.patch".equals(a.getOssRef())));
    }

    @Test
    void sameNumericOwnersAreListedWithDifferentSources() {
        ArtifactDO workitemArtifact = artifact(1L, "WORKITEM", 3L);
        ArtifactDO taskArtifact = artifact(2L, "SCHEDULED_TASK", 3L);
        when(artifactDao.listByWorkitem(100L, 3L)).thenReturn(List.of(workitemArtifact));
        when(artifactDao.listBySource(100L, "SCHEDULED_TASK", 3L, null)).thenReturn(List.of(taskArtifact));

        List<ArtifactVO> workitem = service.listByWorkitem(3L, 100L);
        List<ArtifactVO> task = service.listByOwner(
                new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 3L), 100L);

        assertEquals(List.of(1L), workitem.stream().map(ArtifactVO::getId).toList());
        assertEquals(List.of(2L), task.stream().map(ArtifactVO::getId).toList());
        verify(artifactDao).listByWorkitem(100L, 3L);
        verify(artifactDao).listBySource(100L, "SCHEDULED_TASK", 3L, null);
    }

    @Test
    void ownerAwareDownloadRejectsArtifactOwnedByDifferentSource() {
        when(artifactDao.findBySourceAndId(100L, "SCHEDULED_TASK", 3L, 1L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.getDownloadUrl(1L,
                new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 3L), 100L));

        assertEquals("17010", ex.getCode());
        verify(storage, never()).presignGet(anyString(), anyInt());
    }

    @Test
    void ownerAwareDownloadReturnsHttpPresignedUrlUnchanged() {
        ArtifactDO a = artifact(1L, "WORKITEM", 3L);
        a.setOssRef("b/k");
        when(artifactDao.findWorkitemByTenantAndId(100L, 1L)).thenReturn(a);
        when(storage.presignGet("b/k", 600)).thenReturn("http://172.19.133.124:9000/b/k?X-Amz-Signature=s");

        assertEquals("http://172.19.133.124:9000/b/k?X-Amz-Signature=s",
                service.getDownloadUrl(1L, new ArtifactOwnerRef(ExecutionSourceType.WORKITEM, 3L), 100L));
    }

    @Test
    void ownerAwareDownloadReturnsHttpsPresignedUrlUnchanged() {
        ArtifactDO a = artifact(1L, "WORKITEM", 3L);
        a.setOssRef("b/k");
        when(artifactDao.findWorkitemByTenantAndId(100L, 1L)).thenReturn(a);
        when(storage.presignGet("b/k", 600)).thenReturn("https://172.19.133.124:9000/b/k?X-Amz-Signature=s");

        assertEquals("https://172.19.133.124:9000/b/k?X-Amz-Signature=s",
                service.getDownloadUrl(1L, new ArtifactOwnerRef(ExecutionSourceType.WORKITEM, 3L), 100L));
    }

    private ArtifactDO artifact(long id, String sourceType, long sourceId) {
        ArtifactDO artifact = new ArtifactDO();
        artifact.setId(id);
        artifact.setTenantId(100L);
        artifact.setSourceType(sourceType);
        artifact.setWorkitemId(sourceId);
        artifact.setName("report.md");
        return artifact;
    }

    @Test
    void listByWorkitem_maps_to_vo() {
        ArtifactDO a = new ArtifactDO();
        a.setId(1L); a.setWorkitemId(3L); a.setName("x"); a.setType("LOG");
        when(artifactDao.listByWorkitem(100L, 3L)).thenReturn(List.of(a));
        List<ArtifactVO> vos = service.listByWorkitem(3L, 100L);
        assertEquals(1, vos.size());
        assertEquals("LOG", vos.get(0).getType());
    }

    @Test
    void listByWorkitem_keepsOnlyLatestVersionOfEachLogicalArtifact() {
        ArtifactDO telemetry = new ArtifactDO();
        telemetry.setId(4L); telemetry.setWorkitemId(3L); telemetry.setName("observability/context/files/hash");
        ArtifactDO latest = new ArtifactDO();
        latest.setId(3L); latest.setWorkitemId(3L); latest.setName("artifacts/output/deliverables/report.md");
        ArtifactDO different = new ArtifactDO();
        different.setId(2L); different.setWorkitemId(3L); different.setName("artifacts/output/evidence/test.log");
        ArtifactDO superseded = new ArtifactDO();
        superseded.setId(1L); superseded.setWorkitemId(3L); superseded.setName("artifacts/output/deliverables/report.md");
        when(artifactDao.listByWorkitem(100L, 3L))
                .thenReturn(List.of(telemetry, latest, different, superseded));

        List<ArtifactVO> artifacts = service.listByWorkitem(3L, 100L);

        assertEquals(List.of(3L, 2L), artifacts.stream().map(ArtifactVO::getId).toList());
    }

    @Test
    void retainsSameNamedFilesAcrossDispatchesButDeduplicatesAliasesWithinDispatch() {
        ArtifactDO latest = artifact(4L, "WORKITEM", 3L);
        latest.setDispatchId(20L);
        latest.setName("artifacts/output/deliverables/report.md");
        ArtifactDO alias = artifact(3L, "WORKITEM", 3L);
        alias.setDispatchId(20L);
        alias.setName("output/deliverables/report.md");
        ArtifactDO historical = artifact(2L, "WORKITEM", 3L);
        historical.setDispatchId(10L);
        historical.setName(latest.getName());
        when(artifactDao.listByWorkitem(100L, 3L)).thenReturn(List.of(latest, alias, historical));
        when(artifactDao.listBySource(100L, "SCHEDULED_TASK_RUN", 3L, null)).thenReturn(List.of(latest, alias, historical));

        assertEquals(List.of(4L, 2L), service.listByWorkitem(3L, 100L).stream().map(ArtifactVO::getId).toList());
        assertEquals(List.of(4L, 2L), service.listByOwner(new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK_RUN, 3L), 100L)
                .stream().map(ArtifactVO::getId).toList());
    }

    @Test
    void classifiesLegacyFilesOnReadWithoutRewritingStorage() {
        ArtifactDO snapshot = artifact(1L, "WORKITEM", 3L);
        snapshot.setName("artifacts/attempts/step-1/attempt-2/deliverables/report.md");
        snapshot.setType("FILE");
        ArtifactDO result = artifact(2L, "WORKITEM", 3L);
        result.setName("result/runtime-result.json");
        result.setType("FILE");
        when(artifactDao.listByWorkitem(100L, 3L)).thenReturn(List.of(result, snapshot));
        when(artifactDao.listByDispatch(100L, 10L)).thenReturn(List.of(result, snapshot));

        assertEquals(List.of("RUNTIME", "SNAPSHOT"), service.listByWorkitem(3L, 100L).stream().map(ArtifactVO::getType).toList());
        assertEquals(List.of("RUNTIME", "SNAPSHOT"), service.listByDispatch(10L, 100L).stream().map(ArtifactVO::getType).toList());
        assertEquals("FILE", snapshot.getType());
        verify(artifactDao, never()).insert(any());
    }

    @Test
    void recordClassifiesUntypedFilesWithoutRuntimeMetadata() {
        ReportArtifactRequest req = new ReportArtifactRequest();
        req.setWorkitemId(3L);
        req.setName("result/runtime-result.json");
        req.setType("FILE");
        service.record(req, 100L);
        verify(artifactDao).insert(argThat(a -> "RUNTIME".equals(a.getType())));
    }

    @Test
    void download_returns_presigned_url() {
        ArtifactDO a = new ArtifactDO();
        a.setId(1L); a.setTenantId(100L); a.setOssRef("b/k");
        when(artifactDao.findById(1L)).thenReturn(a);
        when(storage.presignGet("b/k", 600)).thenReturn("https://signed/b/k");
        assertEquals("https://signed/b/k", service.getDownloadUrl(1L, 100L));
    }

    @Test
    void download_returns_http_presigned_url_unchanged() {
        ArtifactDO a = new ArtifactDO();
        a.setId(1L); a.setTenantId(100L); a.setOssRef("b/k");
        when(artifactDao.findById(1L)).thenReturn(a);
        when(storage.presignGet("b/k", 600)).thenReturn("http://172.19.133.124:9000/b/k?X-Amz-Expires=600&X-Amz-Signature=s");

        assertEquals("http://172.19.133.124:9000/b/k?X-Amz-Expires=600&X-Amz-Signature=s",
                service.getDownloadUrl(1L, 100L));
    }

    @Test
    void preview_returns_artifact_bytes_after_tenant_check() {
        ArtifactDO a = new ArtifactDO();
        a.setId(1L); a.setTenantId(100L); a.setName("artifacts/output/report.md"); a.setOssRef("b/k");
        a.setSize(100L);
        when(artifactDao.findById(1L)).thenReturn(a);
        when(storage.get("b/k")).thenReturn("# Report".getBytes(StandardCharsets.UTF_8));

        ArtifactService.PreviewContent preview = service.getPreviewContent(1L, 100L);

        assertEquals("artifacts/output/report.md", preview.getName());
        assertArrayEquals("# Report".getBytes(StandardCharsets.UTF_8), preview.getBytes());
    }

    @Test
    void preview_allows_video_artifacts_after_tenant_check() {
        ArtifactDO a = new ArtifactDO();
        a.setId(1L); a.setTenantId(100L); a.setName("artifacts/output/demo.mp4"); a.setOssRef("b/k");
        a.setSize(1024L);
        when(artifactDao.findById(1L)).thenReturn(a);
        byte[] bytes = new byte[] {0, 1, 2};
        when(storage.get("b/k")).thenReturn(bytes);

        ArtifactService.PreviewContent preview = service.getPreviewContent(1L, 100L);

        assertEquals("artifacts/output/demo.mp4", preview.getName());
        assertArrayEquals(bytes, preview.getBytes());
    }

    @Test
    void preview_allows_html_artifacts_after_tenant_check() {
        ArtifactDO a = new ArtifactDO();
        a.setId(1L); a.setTenantId(100L); a.setName("requirements/plan.html"); a.setOssRef("b/k");
        a.setSize(100L);
        when(artifactDao.findById(1L)).thenReturn(a);
        when(storage.get("b/k")).thenReturn("<html></html>".getBytes(StandardCharsets.UTF_8));

        ArtifactService.PreviewContent preview = service.getPreviewContent(1L, 100L);

        assertEquals("requirements/plan.html", preview.getName());
        assertArrayEquals("<html></html>".getBytes(StandardCharsets.UTF_8), preview.getBytes());
    }

    @Test
    void preview_allows_htm_and_uppercase_html_extensions() {
        ArtifactDO htm = new ArtifactDO();
        htm.setId(1L); htm.setTenantId(100L); htm.setName("requirements/PROTOTYPE.HTM"); htm.setOssRef("b/htm");
        htm.setSize(100L);
        when(artifactDao.findById(1L)).thenReturn(htm);
        when(storage.get("b/htm")).thenReturn("<html></html>".getBytes(StandardCharsets.UTF_8));

        assertNotNull(service.getPreviewContent(1L, 100L));

        ArtifactDO upperHtml = new ArtifactDO();
        upperHtml.setId(2L); upperHtml.setTenantId(100L); upperHtml.setName("requirements/PLAN.HTML"); upperHtml.setOssRef("b/html");
        upperHtml.setSize(100L);
        when(artifactDao.findById(2L)).thenReturn(upperHtml);
        when(storage.get("b/html")).thenReturn("<html></html>".getBytes(StandardCharsets.UTF_8));

        assertNotNull(service.getPreviewContent(2L, 100L));
    }

    @Test
    void preview_large_html_throws_without_reading_storage() {
        ArtifactDO a = new ArtifactDO();
        a.setId(1L); a.setTenantId(100L); a.setName("requirements/big.html");
        a.setSize(20L * 1024L * 1024L + 1L);
        a.setOssRef("b/k");
        when(artifactDao.findById(1L)).thenReturn(a);

        BizException ex = assertThrows(BizException.class, () -> service.getPreviewContent(1L, 100L));

        assertEquals("10001", ex.getCode());
        verify(storage, never()).get(anyString());
    }

    @Test
    void preview_wrong_tenant_throws_without_reading_storage() {
        ArtifactDO a = new ArtifactDO();
        a.setId(1L); a.setTenantId(100L); a.setOssRef("b/k");
        BizException ex = assertThrows(BizException.class, () -> service.getPreviewContent(1L, 999L));

        assertEquals("17010", ex.getCode());
        verify(storage, never()).get(anyString());
    }

    @Test
    void preview_unsupported_type_throws_without_reading_storage() {
        ArtifactDO a = new ArtifactDO();
        a.setId(1L); a.setTenantId(100L); a.setName("artifacts/output/archive.zip"); a.setOssRef("b/k");
        when(artifactDao.findById(1L)).thenReturn(a);

        BizException ex = assertThrows(BizException.class, () -> service.getPreviewContent(1L, 100L));

        assertEquals("10001", ex.getCode());
        verify(storage, never()).get(anyString());
    }

    @Test
    void preview_large_artifact_throws_without_reading_storage() {
        ArtifactDO a = new ArtifactDO();
        a.setId(1L); a.setTenantId(100L); a.setName("artifacts/output/large.png");
        a.setSize(20L * 1024L * 1024L + 1L);
        a.setOssRef("b/k");
        when(artifactDao.findById(1L)).thenReturn(a);

        BizException ex = assertThrows(BizException.class, () -> service.getPreviewContent(1L, 100L));

        assertEquals("10001", ex.getCode());
        verify(storage, never()).get(anyString());
    }

    @Test
    void preview_unknown_size_throws_without_reading_storage() {
        ArtifactDO a = new ArtifactDO();
        a.setId(1L); a.setTenantId(100L); a.setName("artifacts/output/report.md"); a.setOssRef("b/k");
        when(artifactDao.findById(1L)).thenReturn(a);

        BizException ex = assertThrows(BizException.class, () -> service.getPreviewContent(1L, 100L));

        assertEquals("10001", ex.getCode());
        verify(storage, never()).get(anyString());
    }

    @Test
    void download_not_found_throws() {
        BizException ex = assertThrows(BizException.class, () -> service.getDownloadUrl(9L, 100L));
        assertEquals("17010", ex.getCode());
    }

    @Test
    void download_wrong_tenant_throws() {
        ArtifactDO a = new ArtifactDO();
        a.setId(1L); a.setTenantId(100L); a.setOssRef("b/k");
        BizException ex = assertThrows(BizException.class, () -> service.getDownloadUrl(1L, 999L));
        assertEquals("17010", ex.getCode());
        verify(storage, never()).presignGet(anyString(), anyInt());
    }

    @Test
    void downloadRejectsWhenArtifactNotFound() {
        when(artifactDao.findById(1L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.getDownloadUrl(1L, 100L));

        assertEquals("17010", ex.getCode());
        verify(storage, never()).presignGet(anyString(), anyInt());
    }

    @Test
    void previewRejectsWhenArtifactNotFound() {
        when(artifactDao.findById(1L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.getPreviewContent(1L, 100L));

        assertEquals("17010", ex.getCode());
        verify(storage, never()).get(anyString());
    }
}
