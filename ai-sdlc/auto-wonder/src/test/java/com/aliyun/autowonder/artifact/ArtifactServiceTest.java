package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.artifact.dto.ArtifactVO;
import com.aliyun.autowonder.artifact.dto.ReportArtifactRequest;
import com.aliyun.autowonder.common.error.BizException;
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
                        && "PATCH".equals(a.getType())
                        && "autowonder-artifacts-daily/3/out.patch".equals(a.getOssRef())));
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
        when(artifactDao.listByWorkitem(100L, 3L)).thenReturn(List.of(telemetry, latest, different, superseded));

        List<ArtifactVO> artifacts = service.listByWorkitem(3L, 100L);

        assertEquals(List.of(3L, 2L), artifacts.stream().map(ArtifactVO::getId).toList());
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
    void download_upgrades_http_presigned_url_to_https() {
        ArtifactDO a = new ArtifactDO();
        a.setId(1L); a.setTenantId(100L); a.setOssRef("b/k");
        when(artifactDao.findById(1L)).thenReturn(a);
        when(storage.presignGet("b/k", 600)).thenReturn("http://bucket.oss-cn-zhangjiakou.aliyuncs.com/k?Expires=1&Signature=s");

        assertEquals("https://bucket.oss-cn-zhangjiakou.aliyuncs.com/k?Expires=1&Signature=s",
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
    void preview_wrong_tenant_throws_without_reading_storage() {
        ArtifactDO a = new ArtifactDO();
        a.setId(1L); a.setTenantId(100L); a.setOssRef("b/k");
        when(artifactDao.findById(1L)).thenReturn(a);

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
        when(artifactDao.findById(9L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.getDownloadUrl(9L, 100L));
        assertEquals("17010", ex.getCode());
    }

    @Test
    void download_wrong_tenant_throws() {
        ArtifactDO a = new ArtifactDO();
        a.setId(1L); a.setTenantId(100L); a.setOssRef("b/k");
        when(artifactDao.findById(1L)).thenReturn(a);
        BizException ex = assertThrows(BizException.class, () -> service.getDownloadUrl(1L, 999L));
        assertEquals("17010", ex.getCode());
        verify(storage, never()).presignGet(anyString(), anyInt());
    }
}
