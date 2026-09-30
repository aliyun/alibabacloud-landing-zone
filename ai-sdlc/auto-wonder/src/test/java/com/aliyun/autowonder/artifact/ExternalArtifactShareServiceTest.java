package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.StoredObject;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ExternalArtifactShareServiceTest {

    private static final long WORKSPACE_ID = 100L;
    private static final long WORKITEM_ID = 16904L;
    private static final long DISPATCH_ID = 10522L;

    private ArtifactDao artifactDao;
    private WorkitemDao workitemDao;
    private ObjectStorage storage;
    private ExternalArtifactShareService service;

    @BeforeEach
    void setUp() {
        artifactDao = mock(ArtifactDao.class);
        workitemDao = mock(WorkitemDao.class);
        storage = mock(ObjectStorage.class);
        PlatformBrandingService branding = mock(PlatformBrandingService.class);
        when(branding.effectivePublicBaseUrl()).thenReturn("https://auto-wonder.example.com/");
        service = new ExternalArtifactShareService(artifactDao, workitemDao, storage, branding);
    }

    @Test
    void reserveRequestUrlUsesWorkitemTokenWithoutExposingAnyArtifact() {
        when(workitemDao.updateExternalShareTokenIfAbsent(eq(WORKITEM_ID), eq(WORKSPACE_ID), anyString()))
                .thenReturn(1);
        String url = service.requestUrl(workitem(null), 41L);
        assertTrue(url.startsWith("https://auto-wonder.example.com/api/share/workitems/awshare_"));
        assertTrue(url.endsWith("/requests/41"));
        verifyNoInteractions(artifactDao, storage);
    }

    private WorkitemDO workitem(String shareToken) {
        WorkitemDO w = new WorkitemDO();
        w.setId(WORKITEM_ID);
        w.setTenantId(WORKSPACE_ID);
        w.setTitle("【Kering】希望 Flink manage members 支持TF");
        w.setExternalShareToken(shareToken);
        return w;
    }

    private ArtifactDO artifact(long id, String name) {
        ArtifactDO a = new ArtifactDO();
        a.setId(id);
        a.setTenantId(WORKSPACE_ID);
        a.setWorkitemId(WORKITEM_ID);
        a.setDispatchId(DISPATCH_ID);
        a.setName(name);
        a.setType("DELIVERABLE");
        a.setOssRef("test-bucket/t/100/workitem/16904/dispatch/10522/x-" + id);
        a.setSize(12L);
        return a;
    }

    private void stubSnapshotRoundtrip(ArtifactDO a) {
        when(storage.get(a.getOssRef())).thenReturn("content".getBytes());
        when(storage.put(eq("test-bucket"), anyString(), any(byte[].class)))
                .thenAnswer(inv -> new StoredObject(
                        inv.getArgument(0) + "/" + inv.getArgument(1), "md5", 7L));
    }

    @Test
    void exposeByArtifactIdMintsTokenOnceSnapshotsAndMarksExposed() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem(null));
        when(workitemDao.updateExternalShareTokenIfAbsent(eq(WORKITEM_ID), eq(WORKSPACE_ID), anyString()))
                .thenReturn(1);
        ArtifactDO artifact = artifact(301L, "artifacts/output/deliverables/closure-report.md");
        when(artifactDao.findWorkitemByTenantAndId(WORKSPACE_ID, 301L)).thenReturn(artifact);
        stubSnapshotRoundtrip(artifact);

        Map<String, Object> result = service.expose(WORKSPACE_ID, WORKITEM_ID, null, 301L, null);

        assertEquals(301L, result.get("artifactId"));
        assertEquals("deliverables/closure-report.md", result.get("name"));
        String artifactUrl = (String) result.get("artifactUrl");
        String directoryUrl = (String) result.get("directoryUrl");
        assertTrue(artifactUrl.startsWith("https://auto-wonder.example.com/api/share/workitems/awshare_"));
        assertTrue(artifactUrl.endsWith("/artifacts/301"));
        assertEquals(directoryUrl + "/artifacts/301", artifactUrl);
        // 快照落到带随机段的候选 key（独立于 live oss_ref；并发候选互不覆盖）
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(storage).put(eq("test-bucket"), keyCaptor.capture(), eq("content".getBytes()));
        assertTrue(keyCaptor.getValue().matches(
                "t/100/share/16904/301/deliverables/closure-report\\.[A-Za-z0-9_-]{16}\\.md"),
                "candidate key must carry a random segment: " + keyCaptor.getValue());
        // CAS 落库的引用就是刚写入的候选对象
        verify(artifactDao).markExternalExposed(WORKSPACE_ID, WORKITEM_ID, 301L,
                "test-bucket/" + keyCaptor.getValue());
    }

    @Test
    void concurrentFirstExposeLoserCannotOverrideWinnersSnapshot() {
        // 复现评审场景：两个请求同时通过"未暴露"检查，live 对象其间从 v1 更新到 v2。
        // 竞败方（后写 OSS、DB CAS 失败）绝不能覆盖先选定方（v1）的快照内容。
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem("awshare_existing"));
        ArtifactDO artifact = artifact(308L, "deliverables/closure-report.md");
        when(artifactDao.findWorkitemByTenantAndId(WORKSPACE_ID, 308L)).thenReturn(artifact);
        when(artifactDao.findExternalExposed(WORKSPACE_ID, WORKITEM_ID, 308L)).thenReturn(null);
        when(storage.get(artifact.getOssRef())).thenReturn("v2".getBytes());
        List<String> candidateKeys = new java.util.ArrayList<>();
        when(storage.put(eq("test-bucket"), anyString(), any(byte[].class)))
                .thenAnswer(inv -> {
                    candidateKeys.add(inv.getArgument(1));
                    return new StoredObject("test-bucket/" + inv.getArgument(1), "md5", 2L);
                });
        // CAS：首调用胜（写引用），次调用败（0 行）
        when(artifactDao.markExternalExposed(eq(WORKSPACE_ID), eq(WORKITEM_ID), eq(308L), anyString()))
                .thenReturn(1, 0);

        service.expose(WORKSPACE_ID, WORKITEM_ID, null, 308L, null);
        service.expose(WORKSPACE_ID, WORKITEM_ID, null, 308L, null);

        assertEquals(2, candidateKeys.size());
        assertNotEquals(candidateKeys.get(0), candidateKeys.get(1),
                "concurrent candidates must write distinct keys");
    }

    @Test
    void reExposeKeepsExistingSnapshotWithoutCopy() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem("awshare_existing"));
        ArtifactDO alreadyExposed = artifact(302L, "x.md");
        alreadyExposed.setExternalShareRef("test-bucket/t/100/share/16904/302/x.md");
        when(artifactDao.findWorkitemByTenantAndId(WORKSPACE_ID, 302L)).thenReturn(alreadyExposed);
        when(artifactDao.findExternalExposed(WORKSPACE_ID, WORKITEM_ID, 302L)).thenReturn(alreadyExposed);

        Map<String, Object> result = service.expose(WORKSPACE_ID, WORKITEM_ID, null, 302L, null);

        assertEquals("https://auto-wonder.example.com/api/share/workitems/awshare_existing/artifacts/302",
                result.get("artifactUrl"));
        verify(workitemDao, never()).updateExternalShareTokenIfAbsent(anyLong(), anyLong(), anyString());
        verifyNoInteractions(storage);
        verify(artifactDao, never()).markExternalExposed(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void exposeByNameWithoutDispatchResolvesLatestAcrossWorkitem() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem("awshare_existing"));
        ArtifactDO current = artifact(305L, "deliverables/closure-report.md");
        when(artifactDao.listByWorkitem(WORKSPACE_ID, WORKITEM_ID)).thenReturn(List.of(
                current,
                artifact(300L, "artifacts/output/deliverables/closure-report.md")));
        stubSnapshotRoundtrip(current);

        Map<String, Object> result = service.expose(WORKSPACE_ID, WORKITEM_ID, null, null,
                "deliverables/closure-report.md");

        assertEquals(305L, result.get("artifactId"));
        verify(artifactDao).markExternalExposed(eq(WORKSPACE_ID), eq(WORKITEM_ID), eq(305L),
                argThat(ref -> ref != null && ref.startsWith("test-bucket/t/100/share/16904/305/")));
    }

    @Test
    void exposeByNameWithinDispatchNeverFallsBackToEarlierRounds() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem("awshare_existing"));
        // 上一轮同名产物存在于工单级列表，但本轮派发尚未上传：必须报错而不是静默分享旧报告
        when(artifactDao.listByDispatch(WORKSPACE_ID, DISPATCH_ID)).thenReturn(List.of());
        when(artifactDao.listByWorkitem(WORKSPACE_ID, WORKITEM_ID))
                .thenReturn(List.of(artifact(300L, "deliverables/closure-report.md")));

        BizException ex = assertThrows(BizException.class, () -> service.expose(WORKSPACE_ID, WORKITEM_ID,
                DISPATCH_ID, null, "deliverables/closure-report.md"));

        assertEquals(ErrorCode.ARTIFACT_NOT_FOUND.getCode(), ex.getCode());
        assertNotNull(ex.getMessage());
        verify(artifactDao, never()).listByWorkitem(anyLong(), anyLong());
        verifyNoInteractions(storage);
        verify(artifactDao, never()).markExternalExposed(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void exposeByNameWithinDispatchResolvesOnlyThatRound() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem("awshare_existing"));
        ArtifactDO thisRound = artifact(306L, "deliverables/closure-report.md");
        when(artifactDao.listByDispatch(WORKSPACE_ID, DISPATCH_ID)).thenReturn(List.of(thisRound));
        // 工单级列表里存在更新 id 的同名产物（另一派发），dispatch 圈定后不应命中
        when(artifactDao.listByWorkitem(WORKSPACE_ID, WORKITEM_ID)).thenReturn(List.of(
                artifact(307L, "deliverables/closure-report.md"), thisRound));
        stubSnapshotRoundtrip(thisRound);

        Map<String, Object> result = service.expose(WORKSPACE_ID, WORKITEM_ID, DISPATCH_ID, null,
                "deliverables/closure-report.md");

        assertEquals(306L, result.get("artifactId"));
        verify(artifactDao, never()).listByWorkitem(anyLong(), anyLong());
    }

    @Test
    void snapshotFailsWhenLiveObjectMissing() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem("awshare_existing"));
        ArtifactDO artifact = artifact(303L, "y.md");
        when(artifactDao.findWorkitemByTenantAndId(WORKSPACE_ID, 303L)).thenReturn(artifact);
        when(storage.get(artifact.getOssRef())).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.expose(WORKSPACE_ID, WORKITEM_ID, null, 303L, null));
        assertEquals(ErrorCode.ARTIFACT_NOT_FOUND.getCode(), ex.getCode());
        verify(artifactDao, never()).markExternalExposed(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void concurrentTokenWriteLoserReReadsWinnerToken() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem(null), workitem("awshare_winner"));
        when(workitemDao.updateExternalShareTokenIfAbsent(eq(WORKITEM_ID), eq(WORKSPACE_ID), anyString()))
                .thenReturn(0);
        ArtifactDO artifact = artifact(304L, "y.md");
        when(artifactDao.findWorkitemByTenantAndId(WORKSPACE_ID, 304L)).thenReturn(artifact);
        stubSnapshotRoundtrip(artifact);

        Map<String, Object> result = service.expose(WORKSPACE_ID, WORKITEM_ID, null, 304L, null);

        assertTrue(result.get("artifactUrl").toString().contains("awshare_winner"));
    }

    @Test
    void rejectsWrongWorkspaceUnknownArtifactAndMissingSelector() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem("awshare_existing"));
        BizException wrongWorkspace = assertThrows(BizException.class,
                () -> service.expose(999L, WORKITEM_ID, null, 301L, null));
        assertEquals(ErrorCode.WORKITEM_NOT_FOUND.getCode(), wrongWorkspace.getCode());

        when(artifactDao.findWorkitemByTenantAndId(WORKSPACE_ID, 404L)).thenReturn(null);
        BizException missingArtifact = assertThrows(BizException.class,
                () -> service.expose(WORKSPACE_ID, WORKITEM_ID, null, 404L, null));
        assertEquals(ErrorCode.ARTIFACT_NOT_FOUND.getCode(), missingArtifact.getCode());

        BizException missingSelector = assertThrows(BizException.class,
                () -> service.expose(WORKSPACE_ID, WORKITEM_ID, null, null, " "));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), missingSelector.getCode());

        BizException badDispatchId = assertThrows(BizException.class,
                () -> service.expose(WORKSPACE_ID, WORKITEM_ID, 0L, null, "x.md"));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), badDispatchId.getCode());
    }

    @Test
    void loadContentServesSnapshotRefNeverLiveRef() {
        ArtifactDO artifact = artifact(301L, "a.md");
        artifact.setExternalShareRef("test-bucket/t/100/share/16904/301/a.md");
        when(storage.get(artifact.getExternalShareRef())).thenReturn("# frozen".getBytes());

        assertEquals("# frozen", new String(service.loadContent(artifact)));
        verify(storage, never()).get(artifact.getOssRef());
    }

    @Test
    void loadContentFailsWithoutSnapshotOrWhenObjectMissing() {
        BizException noSnapshot = assertThrows(BizException.class,
                () -> service.loadContent(artifact(1L, "a.md")));
        assertEquals(ErrorCode.ARTIFACT_NOT_FOUND.getCode(), noSnapshot.getCode());

        ArtifactDO artifact = artifact(2L, "b.md");
        artifact.setExternalShareRef("test-bucket/share/b.md");
        when(storage.get("test-bucket/share/b.md")).thenReturn(null);
        BizException missing = assertThrows(BizException.class, () -> service.loadContent(artifact));
        assertEquals(ErrorCode.ARTIFACT_NOT_FOUND.getCode(), missing.getCode());
    }

    @Test
    void newTokenIsPrefixedAndUnguessableShaped() {
        String token = ExternalArtifactShareService.newToken();
        assertTrue(token.startsWith(ExternalArtifactShareService.TOKEN_PREFIX));
        assertEquals(43, token.substring(ExternalArtifactShareService.TOKEN_PREFIX.length()).length());
        assertNotEquals(token, ExternalArtifactShareService.newToken());
    }
    @Test
    void pinnedDigestRejectsRewrittenBytesBeforeExposure() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem("awshare_existing"));
        ArtifactDO artifact = artifact(309L, "report.md");
        when(artifactDao.findWorkitemByTenantAndId(WORKSPACE_ID, 309L)).thenReturn(artifact);
        when(storage.get(artifact.getOssRef())).thenReturn("rewritten secret".getBytes());
        assertThrows(BizException.class, () -> service.expose(WORKSPACE_ID, WORKITEM_ID, DISPATCH_ID,
                309L, null, "a".repeat(64)));
        verify(storage, never()).put(anyString(), anyString(), any(byte[].class));
        verify(artifactDao, never()).markExternalExposed(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void pinnedDigestVerifiesFrozenSnapshotEvenAfterLiveOverwrite() throws Exception {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem("awshare_existing"));
        ArtifactDO artifact = artifact(310L, "report.md");
        artifact.setExternalShareRef("bucket/frozen");
        when(artifactDao.findWorkitemByTenantAndId(WORKSPACE_ID, 310L)).thenReturn(artifact);
        when(artifactDao.findExternalExposed(WORKSPACE_ID, WORKITEM_ID, 310L)).thenReturn(artifact);
        when(storage.get("bucket/frozen")).thenReturn("vetted".getBytes());
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("vetted".getBytes()));
        assertEquals("SHARED", service.expose(WORKSPACE_ID, WORKITEM_ID, DISPATCH_ID, 310L, null, digest).get("status"));
        assertThrows(BizException.class, () -> service.expose(WORKSPACE_ID, WORKITEM_ID, DISPATCH_ID,
                310L, null, "a".repeat(64)));
        verify(storage, never()).get(artifact.getOssRef());
    }

    @Test
    void artifactIdCannotBypassDispatchScope() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem("awshare_existing"));
        ArtifactDO artifact = artifact(311L, "report.md");
        artifact.setDispatchId(DISPATCH_ID - 1);
        when(artifactDao.findWorkitemByTenantAndId(WORKSPACE_ID, 311L)).thenReturn(artifact);
        assertThrows(BizException.class, () -> service.expose(WORKSPACE_ID, WORKITEM_ID, DISPATCH_ID, 311L, null));
        verifyNoInteractions(storage);
    }

}
