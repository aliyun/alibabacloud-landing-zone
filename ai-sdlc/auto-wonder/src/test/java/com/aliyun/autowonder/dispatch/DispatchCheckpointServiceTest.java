package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.storage.StoredObject;
import com.aliyun.autowonder.taskpackage.TaskArtifactRef;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchCheckpointServiceTest {

    @Test
    void freshSideInteractionKeepsItsModeWithoutAResumeSource() {
        DispatchCheckpointService service = new DispatchCheckpointService(
                mock(DispatchCheckpointDao.class), mock(DispatchRuntimeEventDao.class),
                mock(DispatchDao.class), mock(ObjectStorage.class), new OssProperties());
        DispatchDO dispatch = new DispatchDO();
        dispatch.setTenantId(100L);
        dispatch.setResumeMode("SIDE_INTERACTION");

        ResumeDescriptor descriptor = service.descriptor(dispatch);

        assertNotNull(descriptor);
        assertEquals("SIDE_INTERACTION", descriptor.mode());
        assertEquals("FORK", descriptor.sessionBehavior());
        assertNull(descriptor.sourceDispatchId());
        assertNull(descriptor.providerSessionId());
    }

    @Test
    void canonicalInteractionUsesBackwardCompatibleWireMode() {
        DispatchCheckpointService service = new DispatchCheckpointService(
                mock(DispatchCheckpointDao.class), mock(DispatchRuntimeEventDao.class),
                mock(DispatchDao.class), mock(ObjectStorage.class), new OssProperties());
        DispatchDO dispatch = new DispatchDO();
        dispatch.setTenantId(100L);
        dispatch.setResumeMode("CANONICAL_INTERACTION");

        ResumeDescriptor descriptor = service.descriptor(dispatch);

        assertNotNull(descriptor);
        assertEquals("SIDE_INTERACTION", descriptor.mode());
        assertEquals("CANONICAL", descriptor.sessionBehavior());
    }

    @Test
    void storePrunesCheckpointsOlderThanTheLatestTwo() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        OssProperties properties = new OssProperties();
        properties.setArtifactBucket("artifact-bucket");
        DispatchCheckpointService service = new DispatchCheckpointService(
                dao, runtimeEventDao, dispatchDao, storage, properties);
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(55L);
        dispatch.setTenantId(100L);
        dispatch.setWorkitemId(200L);
        dispatch.setAgentId(300L);

        DispatchCheckpointDO obsolete = new DispatchCheckpointDO();
        obsolete.setId(1L);
        obsolete.setOssRef("artifact-bucket/old-checkpoint");
        when(dao.findByDispatchAndSeq(100L, 55L, 3L)).thenReturn(null);
        when(storage.put(eq("artifact-bucket"), anyString(), any(byte[].class)))
                .thenReturn(new StoredObject("artifact-bucket/new-checkpoint", "md5", 7L));
        when(dao.listObsolete(100L, 55L, 2)).thenReturn(List.of(obsolete));

        service.store(dispatch, 3L, "codex", "session", "runtime", "step", new byte[]{1});

        verify(storage).delete("artifact-bucket/old-checkpoint");
        verify(dao).deleteById(100L, 55L, 1L);
    }

    @Test
    void recoveryDescriptorKeepsRecoveryIdentityWhenNoCheckpointExists() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        OssProperties properties = new OssProperties();
        properties.setArtifactBucket("artifact-bucket");
        DispatchCheckpointService service = new DispatchCheckpointService(
                dao, runtimeEventDao, dispatchDao, storage, properties);
        DispatchDO dispatch = new DispatchDO();
        dispatch.setTenantId(100L);
        dispatch.setResumeMode("RECOVERY");
        dispatch.setResumeFromDispatchId(55L);
        dispatch.setDeliverySourceDispatchId(99L);
        when(dao.findLatestByDispatch(100L, 55L)).thenReturn(null);

        ResumeDescriptor descriptor = service.descriptor(dispatch);

        assertNotNull(descriptor);
        assertEquals("RECOVERY", descriptor.mode());
        assertEquals(55L, descriptor.sourceDispatchId());
        verify(dispatchDao, never()).findById(99L);
        assertNull(descriptor.providerSessionId());
        assertNull(descriptor.checkpointDownloadUrl());
    }

    @Test
    void descriptorFallsBackToPinnedProviderSessionBeforeFirstCheckpointUpload() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        OssProperties properties = new OssProperties();
        DispatchCheckpointService service = new DispatchCheckpointService(
                dao, runtimeEventDao, dispatchDao, storage, properties);
        DispatchDO dispatch = new DispatchDO();
        dispatch.setTenantId(100L);
        dispatch.setResumeMode("RETURNING_WORKER");
        dispatch.setResumeFromDispatchId(55L);
        DispatchRuntimeEventDO pinned = new DispatchRuntimeEventDO();
        pinned.setDetailJson("{\"sessionId\":\"session-55\",\"provider\":\"codex\"}");
        when(runtimeEventDao.findLatestByDispatchAndType(
                100L, 55L, "agent.session_pinned")).thenReturn(pinned);

        ResumeDescriptor descriptor = service.descriptor(dispatch);

        assertNotNull(descriptor);
        assertEquals("codex", descriptor.provider());
        assertEquals("session-55", descriptor.providerSessionId());
        assertNull(descriptor.checkpointDownloadUrl());
        assertTrue(service.hasResumableSession(100L, 55L));
    }

    @Test
    void recoveryDescriptorIncludesLatestAndPreviousCheckpointCandidates() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        OssProperties properties = new OssProperties();
        DispatchCheckpointService service = new DispatchCheckpointService(
                dao, runtimeEventDao, dispatchDao, storage, properties);
        DispatchDO dispatch = new DispatchDO();
        dispatch.setTenantId(100L);
        dispatch.setResumeMode("RECOVERY");
        dispatch.setResumeFromDispatchId(55L);
        DispatchCheckpointDO latest = checkpoint(2L, "bucket/latest", "latest-sha");
        DispatchCheckpointDO previous = checkpoint(1L, "bucket/previous", "previous-sha");
        when(dao.listLatestByDispatch(100L, 55L, 2)).thenReturn(List.of(latest, previous));
        when(storage.exists("bucket/latest")).thenReturn(true);
        when(storage.exists("bucket/previous")).thenReturn(true);
        when(storage.presignGet("bucket/latest", 600)).thenReturn("https://oss/latest");
        when(storage.presignGet("bucket/previous", 600)).thenReturn("https://oss/previous");

        ResumeDescriptor descriptor = service.descriptor(dispatch);

        assertEquals("https://oss/latest", descriptor.checkpointDownloadUrl());
        assertEquals(2, descriptor.checkpointCandidates().size());
        assertEquals(2L, descriptor.checkpointCandidates().get(0).getCheckpointSeq());
        assertEquals("https://oss/previous", descriptor.checkpointCandidates().get(1).getDownloadUrl());
    }

    @Test
    void recoveryDescriptorFallsBackThroughResumeLineageWhenDirectSourceHasNoCheckpoint() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchRuntimeEventDao runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        OssProperties properties = new OssProperties();
        DispatchCheckpointService service = new DispatchCheckpointService(
                dao, runtimeEventDao, dispatchDao, storage, properties);
        DispatchDO recovery = new DispatchDO();
        recovery.setTenantId(100L);
        recovery.setResumeMode("RECOVERY");
        recovery.setResumeFromDispatchId(55L);
        DispatchDO directSource = new DispatchDO();
        directSource.setId(55L);
        directSource.setTenantId(100L);
        directSource.setResumeFromDispatchId(44L);
        DispatchCheckpointDO ancestor = checkpoint(7L, "bucket/ancestor", "ancestor-sha");
        when(dispatchDao.findById(55L)).thenReturn(directSource);
        when(dao.listLatestByDispatch(100L, 44L, 2)).thenReturn(List.of(ancestor));
        when(storage.exists("bucket/ancestor")).thenReturn(true);
        when(storage.presignGet("bucket/ancestor", 600)).thenReturn("https://oss/ancestor");

        ResumeDescriptor descriptor = service.descriptor(recovery);

        assertEquals(44L, descriptor.sourceDispatchId());
        assertEquals("https://oss/ancestor", descriptor.checkpointDownloadUrl());
        assertEquals(1, descriptor.checkpointCandidates().size());
        assertEquals(7L, descriptor.checkpointCandidates().get(0).getCheckpointSeq());
    }

    @Test
    void recoveryDescriptorSkipsMissingCheckpointAndFallsBackThroughResumeLineage() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchRuntimeEventDao runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = new DispatchCheckpointService(
                dao, runtimeEventDao, dispatchDao, storage, new OssProperties());
        DispatchDO recovery = new DispatchDO();
        recovery.setTenantId(100L);
        recovery.setResumeMode("RECOVERY");
        recovery.setResumeFromDispatchId(55L);
        DispatchDO directSource = new DispatchDO();
        directSource.setId(55L);
        directSource.setTenantId(100L);
        directSource.setResumeFromDispatchId(44L);
        DispatchCheckpointDO missing = checkpoint(8L, "old-bucket/missing", "missing-sha");
        DispatchCheckpointDO ancestor = checkpoint(7L, "new-bucket/ancestor", "ancestor-sha");
        when(dao.listLatestByDispatch(100L, 55L, 2)).thenReturn(List.of(missing));
        when(dao.listLatestByDispatch(100L, 44L, 2)).thenReturn(List.of(ancestor));
        when(storage.exists("old-bucket/missing")).thenReturn(false);
        when(storage.exists("new-bucket/ancestor")).thenReturn(true);
        when(dispatchDao.findById(55L)).thenReturn(directSource);
        when(storage.presignGet("new-bucket/ancestor", 600)).thenReturn("https://oss/ancestor");

        ResumeDescriptor descriptor = service.descriptor(recovery);

        assertEquals(44L, descriptor.sourceDispatchId());
        assertEquals("https://oss/ancestor", descriptor.checkpointDownloadUrl());
        assertEquals(1, descriptor.checkpointCandidates().size());
    }

    @Test
    void returningWorkerFindsPinnedSessionThroughResumeLineage() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchRuntimeEventDao runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = new DispatchCheckpointService(
                dao, runtimeEventDao, dispatchDao, storage, new OssProperties());
        DispatchDO directSource = new DispatchDO();
        directSource.setId(55L);
        directSource.setTenantId(100L);
        directSource.setResumeFromDispatchId(44L);
        DispatchRuntimeEventDO pinned = new DispatchRuntimeEventDO();
        pinned.setDetailJson("{\"sessionId\":\"session-44\",\"provider\":\"codex\"}");
        when(dispatchDao.findById(55L)).thenReturn(directSource);
        when(runtimeEventDao.findLatestByDispatchAndType(
                100L, 44L, "agent.session_pinned")).thenReturn(pinned);

        assertTrue(service.hasResumableSession(100L, 55L));
    }

    @Test
    void durableReceiptMayMatchAnyStoredCheckpointSequenceAndHash() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchCheckpointDO stored = checkpoint(7L, "bucket/checkpoint", "abc");
        when(dao.findByDispatchAndSeq(100L, 55L, 7L)).thenReturn(stored);
        DispatchCheckpointService service = new DispatchCheckpointService(
                dao, mock(DispatchRuntimeEventDao.class), mock(DispatchDao.class),
                mock(ObjectStorage.class), new OssProperties());

        assertTrue(service.matchesDurableReceipt(100L, 55L, 7L, "sha256:abc"));
        assertFalse(service.matchesDurableReceipt(100L, 55L, 6L, "sha256:abc"));
        assertFalse(service.matchesDurableReceipt(100L, 55L, 7L, "sha256:wrong"));
        verify(dao, never()).findLatestByDispatch(100L, 55L);
    }

    @Test
    void exposesMaterializableRepoBaselineFromDurableCheckpointArchive() throws Exception {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        OssProperties properties = new OssProperties();
        properties.setArtifactBucket("artifact-bucket");
        DispatchCheckpointService service = new DispatchCheckpointService(
                dao, mock(DispatchRuntimeEventDao.class), dispatchDao, storage, properties);
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(55L);
        dispatch.setTenantId(100L);
        dispatch.setWorkitemId(200L);
        dispatch.setAgentId(300L);
        byte[] archive = checkpointArchive("""
                {
                  "schemaVersion":"autowonder.runtimeCheckpoint.v1",
                  "repos":[{
                    "name":"auto-wonder",
                    "baseCommit":"1111111111111111111111111111111111111111",
                    "headCommit":"2222222222222222222222222222222222222222",
                    "branch":"aw/可靠恢复"
                  }]
                }
                """);

        DispatchCheckpointDO stored = service.store(dispatch, 7L, "codex", "session",
                "runtime", "step", archive);
        when(dao.listLatestByDispatch(100L, 55L, 2)).thenReturn(List.of(stored));

        TaskArtifactRef revision = service.findRepoRevisionArtifact(100L, 55L);

        assertNotNull(revision);
        assertTrue(revision.getName().endsWith("deliverables/runtime-source-revision.json"));
        String normalized = new String(storage.get(revision.getOssRef()), StandardCharsets.UTF_8);
        assertTrue(normalized.contains("\"branch\":\"aw/可靠恢复\""));
        assertTrue(normalized.contains("1111111111111111111111111111111111111111"));
        assertFalse(normalized.contains("\"baseCommit\""),
                "checkpoint checkout base must not be exposed as authoritative delivery lineage");
        assertFalse(normalized.contains("2222222222222222222222222222222222222222"),
                "local-only checkpoint HEAD must be restored from the bundle, not used as package ref");
    }

    @Test
    void repoRevisionFallsBackWhenLatestCheckpointObjectIsUnavailable() throws Exception {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        OssProperties properties = new OssProperties();
        properties.setArtifactBucket("artifact-bucket");
        DispatchCheckpointService service = new DispatchCheckpointService(
                dao, mock(DispatchRuntimeEventDao.class), mock(DispatchDao.class), storage, properties);
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(55L); dispatch.setTenantId(100L); dispatch.setWorkitemId(200L); dispatch.setAgentId(300L);
        byte[] archive = checkpointArchive("""
                {"schemaVersion":"autowonder.runtimeCheckpoint.v1","repos":[{
                  "name":"auto-wonder",
                  "baseCommit":"1111111111111111111111111111111111111111",
                  "headCommit":"2222222222222222222222222222222222222222",
                  "branch":"aw/reliable"
                }]}
                """);
        DispatchCheckpointDO previous = service.store(dispatch, 7L, "codex", "session",
                "runtime", "step", archive);
        DispatchCheckpointDO unavailableLatest = checkpoint(8L, "artifact-bucket/missing", "missing-sha");
        when(dao.listLatestByDispatch(100L, 55L, 2))
                .thenReturn(List.of(unavailableLatest, previous));

        TaskArtifactRef revision = service.findRepoRevisionArtifact(100L, 55L);

        assertNotNull(revision);
        assertTrue(new String(storage.get(revision.getOssRef()), StandardCharsets.UTF_8)
                .contains("\"branch\":\"aw/reliable\""));
    }

    @Test
    void missingOptionalRepoStateSidecarFallsBackWithoutGettingMissingObject() throws Exception {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = new DispatchCheckpointService(
                dao, mock(DispatchRuntimeEventDao.class), mock(DispatchDao.class), storage,
                new OssProperties());
        String checkpointRef = "artifact-bucket/t/100/checkpoint-7.tar.gz";
        String sidecarRef = checkpointRef + ".repo-state.json";
        byte[] archive = checkpointArchive("""
                {"schemaVersion":"autowonder.runtimeCheckpoint.v1","repos":[{
                  "name":"auto-wonder",
                  "baseCommit":"1111111111111111111111111111111111111111",
                  "headCommit":"2222222222222222222222222222222222222222"
                }]}
                """);
        DispatchCheckpointDO stored = checkpoint(7L, checkpointRef, sha256Hex(archive));
        when(dao.listLatestByDispatch(100L, 55L, 2)).thenReturn(List.of(stored));
        when(storage.exists(sidecarRef)).thenReturn(false);
        when(storage.get(checkpointRef)).thenReturn(archive);
        when(storage.put(eq("artifact-bucket"), eq("t/100/checkpoint-7.tar.gz.repo-state.json"), any()))
                .thenReturn(new StoredObject(sidecarRef, "md5", 1));

        TaskArtifactRef revision = service.findRepoRevisionArtifact(100L, 55L);

        assertNotNull(revision);
        assertEquals(sidecarRef, revision.getOssRef());
        verify(storage).exists(sidecarRef);
        verify(storage, never()).get(sidecarRef);
        verify(storage).get(checkpointRef);
    }

    @Test
    void repairsLegacySidecarThatExposedLocalOnlyCheckpointHead() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        OssProperties properties = new OssProperties();
        properties.setArtifactBucket("artifact-bucket");
        DispatchCheckpointService service = new DispatchCheckpointService(
                dao, mock(DispatchRuntimeEventDao.class), mock(DispatchDao.class), storage, properties);
        String checkpointRef = "artifact-bucket/t/100/checkpoint-7.tar.gz";
        String legacy = """
                {"schemaVersion":"autowonder.checkpointSourceRevision.v1","repositories":[{
                  "name":"auto-wonder",
                  "baseCommit":"1111111111111111111111111111111111111111",
                  "headCommit":"2222222222222222222222222222222222222222",
                  "branch":"fix/local-only"
                }]}
                """;
        storage.put("artifact-bucket", "t/100/checkpoint-7.tar.gz.repo-state.json",
                legacy.getBytes(StandardCharsets.UTF_8));
        DispatchCheckpointDO stored = checkpoint(7L, checkpointRef, "unused");
        when(dao.listLatestByDispatch(100L, 55L, 2)).thenReturn(List.of(stored));

        TaskArtifactRef revision = service.findRepoRevisionArtifact(100L, 55L);

        assertNotNull(revision);
        String repaired = new String(storage.get(revision.getOssRef()), StandardCharsets.UTF_8);
        assertTrue(repaired.contains("1111111111111111111111111111111111111111"));
        assertFalse(repaired.contains("2222222222222222222222222222222222222222"));
    }

    private static DispatchCheckpointDO checkpoint(long seq, String ossRef, String sha) {
        DispatchCheckpointDO checkpoint = new DispatchCheckpointDO();
        checkpoint.setCheckpointSeq(seq);
        checkpoint.setOssRef(ossRef);
        checkpoint.setSha256(sha);
        return checkpoint;
    }

    private static byte[] checkpointArchive(String checkpointJson) throws Exception {
        byte[] content = checkpointJson.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        byte[] header = new byte[512];
        byte[] name = "checkpoint.json".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(name, 0, header, 0, name.length);
        byte[] size = String.format("%011o", content.length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, header, 124, size.length);
        header[156] = '0';
        tar.write(header);
        tar.write(content);
        int padding = (512 - content.length % 512) % 512;
        tar.write(new byte[padding + 1024]);
        ByteArrayOutputStream gzip = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gzip)) {
            out.write(tar.toByteArray());
        }
        return gzip.toByteArray();
    }

    private static String sha256Hex(byte[] content) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
    }
}
