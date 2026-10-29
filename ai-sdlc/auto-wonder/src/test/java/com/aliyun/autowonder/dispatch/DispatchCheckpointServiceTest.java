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

    @org.junit.jupiter.api.Test void confirmedPauseAcknowledgementSurvivesCheckpointCollection() {
        var dao=org.mockito.Mockito.mock(DispatchCheckpointDao.class);
        var service=new DispatchCheckpointService(dao,org.mockito.Mockito.mock(DispatchRuntimeEventDao.class),
                org.mockito.Mockito.mock(DispatchDao.class),org.mockito.Mockito.mock(com.aliyun.autowonder.storage.ObjectStorage.class),
                new com.aliyun.autowonder.storage.OssProperties());
        var store=org.mockito.Mockito.mock(DispatchResultStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"resultStore",store);
        String hash="sha256:"+"a".repeat(64);String identity="pause:100:7:55:42:"+hash;
        String resultId=java.util.UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String digest;
        try {digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(Exception e){throw new AssertionError(e);}
        var ack=new com.aliyun.autowonder.json.JSONObject();ack.put("reasonCode","PAUSE_CONFIRMED");
        org.mockito.Mockito.when(store.find(100,55,resultId)).thenReturn(new DispatchResultStore.Decision(7,digest,ack,null));
        org.junit.jupiter.api.Assertions.assertTrue(service.confirmPauseReceipt(100,7,55,42,hash));
        org.mockito.Mockito.verifyNoInteractions(dao);
        org.junit.jupiter.api.Assertions.assertFalse(service.confirmPauseReceipt(100,7,55,43,hash));
    }

    @org.junit.jupiter.api.Test void confirmedPauseRejectsAnotherBoundaryAndItsTailUploads() {
        var dao=org.mockito.Mockito.mock(DispatchCheckpointDao.class);
        var service=new DispatchCheckpointService(dao,org.mockito.Mockito.mock(DispatchRuntimeEventDao.class),
                org.mockito.Mockito.mock(DispatchDao.class),org.mockito.Mockito.mock(com.aliyun.autowonder.storage.ObjectStorage.class),
                new com.aliyun.autowonder.storage.OssProperties());
        var pin=new DispatchCheckpointDO();pin.setCheckpointSeq(42L);pin.setExecutorId(7L);pin.setSha256("abc");
        var runtimeStore=org.mockito.Mockito.mock(DispatchResultStore.class);
        org.mockito.Mockito.when(runtimeStore.transaction(org.mockito.ArgumentMatchers.any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(0)).get());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"resultStore",runtimeStore);
        org.mockito.Mockito.when(dao.findPauseCheckpoint(100,55,7L)).thenReturn(pin);
        org.junit.jupiter.api.Assertions.assertFalse(service.confirmPauseReceipt(100,7,55,43,"sha256:other"));
        org.junit.jupiter.api.Assertions.assertFalse(service.confirmPauseReceipt(100,8,55,42,"sha256:abc"));
        var d=new DispatchDO();d.setTenantId(100L);d.setId(55L);d.setStatus(DispatchStatus.PAUSED);d.setExecutorId(7L);
        org.junit.jupiter.api.Assertions.assertThrows(DispatchCheckpointService.CheckpointConflictException.class,
                ()->service.store(d,43,null,null,null,null,new byte[]{1}));
    }

    @Test
    void completionUploadPinsIdentityBeforeReturningAndUsesImmutableObjectKey() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchResultStore store = mock(DispatchResultStore.class);
        when(store.transaction(any())).thenAnswer(call -> ((java.util.function.Supplier<?>)call.getArgument(0)).get());
        when(storage.put(anyString(), anyString(), any())).thenReturn(new StoredObject("bucket/immutable", "md5", 3L));
        OssProperties properties = new OssProperties();
        properties.setArtifactBucket("bucket");
        DispatchCheckpointService service = new DispatchCheckpointService(dao, mock(DispatchRuntimeEventDao.class),
                mock(DispatchDao.class), storage, properties);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "resultStore", store);
        DispatchDO dispatch = new DispatchDO(); dispatch.setTenantId(100L); dispatch.setId(55L);
        dispatch.setWorkitemId(200L); dispatch.setAgentId(9L);
        String resultId="b55aa1a7-43d5-4452-b39a-278554b67942";
        DispatchCheckpointDO receipt=service.store(dispatch,7,null,null,null,null,"abc".getBytes(),resultId);
        assertEquals(resultId,receipt.getCompletionResultId());
        var order=org.mockito.Mockito.inOrder(store,storage,dao);
        order.verify(store).lockDispatch(100,55);
        order.verify(storage).put(anyString(),argThat(key -> key.contains("checkpoint-7-") && key.endsWith(".tar.gz")),any());
        order.verify(dao).insert(argThat(row -> resultId.equals(row.getCompletionResultId())));
    }

    @Test
    void repeatedSequenceCannotChangeTheBytesBehindAReceipt() {
        DispatchCheckpointDao dao=mock(DispatchCheckpointDao.class);
        ObjectStorage storage=mock(ObjectStorage.class);
        DispatchCheckpointDO old=checkpoint(7,"bucket/original","different-hash");
        when(dao.findByDispatchAndSeq(100L,55L,7L)).thenReturn(old);
        DispatchCheckpointService service=new DispatchCheckpointService(dao,mock(DispatchRuntimeEventDao.class),
                mock(DispatchDao.class),storage,new OssProperties());
        DispatchDO dispatch=new DispatchDO(); dispatch.setTenantId(100L); dispatch.setId(55L);
        org.junit.jupiter.api.Assertions.assertThrows(DispatchCheckpointService.CheckpointConflictException.class,
                () -> service.store(dispatch,7,null,null,null,null,"different".getBytes()));
        org.mockito.Mockito.verifyNoInteractions(storage);
    }

    @Test
    void recoveryProjectsLegacyCheckpointThroughRetryLineageAndKeepsWireChecksumConsistent() throws Exception {
        LegacyCheckpointNormalizerTest fixture = new LegacyCheckpointNormalizerTest();
        DispatchCheckpointDO checkpoint = fixture.source(LegacyCheckpointNormalizerTest.PREFIX);
        when(fixture.artifacts.listByDispatch(10002L, 13378L)).thenReturn(List.of(fixture.artifact()));
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchCheckpointService service = new DispatchCheckpointService(dao,
                mock(DispatchRuntimeEventDao.class), dispatchDao, fixture.storage, new OssProperties());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "legacyCheckpointNormalizer",
                new LegacyCheckpointNormalizer(fixture.artifacts, fixture.storage));
        long[] lineage = {13407L, 13406L, 13403L, 13378L};
        for (int i = 0; i < lineage.length - 1; i++) {
            DispatchDO row = new DispatchDO(); row.setTenantId(10002L); row.setId(lineage[i]);
            row.setResumeFromDispatchId(lineage[i + 1]);
            when(dispatchDao.findById(lineage[i])).thenReturn(row);
        }
        when(dao.listLatestByDispatch(10002L, 13378L, 2)).thenReturn(List.of(checkpoint));
        DispatchDO retry = new DispatchDO(); retry.setTenantId(10002L); retry.setWorkitemId(55411L);
        retry.setResumeMode("RECOVERY"); retry.setResumeFromDispatchId(13407L);

        ResumeDescriptor result = service.descriptor(retry);

        assertEquals(13378L, result.sourceDispatchId());
        assertNotEquals("sha256:" + checkpoint.getSha256(), result.checkpointSha256());
        assertEquals(result.checkpointSha256(), result.checkpointCandidates().get(0).getSha256());
        assertEquals(result.checkpointDownloadUrl(), result.checkpointCandidates().get(0).getDownloadUrl());
        assertEquals(123L, result.checkpointSeq());
        assertEquals(checkpoint.getSha256(), LegacyCheckpointNormalizerTest.sha(fixture.storage.get(checkpoint.getOssRef())));
    }

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
    void storeMarksObsoleteMetadataWithoutDeletingObjectsBeforeCommit() {
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

        verify(storage, never()).delete("artifact-bucket/old-checkpoint");
        verify(storage, never()).delete("artifact-bucket/old-checkpoint.compat-exact-ref-v1.tar.gz");
        verify(dao).markGarbage(100L, 55L, 1L);
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
    void degradedContinuousDescriptorNeverExposesCheckpointNativeSession() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = new DispatchCheckpointService(dao,
                mock(DispatchRuntimeEventDao.class), mock(DispatchDao.class), storage, new OssProperties());
        DispatchDO dispatch = new DispatchDO(); dispatch.setTenantId(100L);
        dispatch.setResumeMode("DEGRADED_CONTINUOUS"); dispatch.setResumeFromDispatchId(55L);
        DispatchCheckpointDO checkpoint = checkpoint(1L, "bucket/checkpoint", "abc");
        checkpoint.setProvider("codex"); checkpoint.setProviderSessionId("native-session");
        when(dao.listLatestByDispatch(100L, 55L, 2)).thenReturn(List.of(checkpoint));
        when(storage.exists("bucket/checkpoint")).thenReturn(true);
        when(storage.presignGet("bucket/checkpoint", 600)).thenReturn("https://oss/checkpoint");

        ResumeDescriptor descriptor = service.descriptor(dispatch);

        assertNull(descriptor.providerSessionId());
        assertEquals("https://oss/checkpoint", descriptor.checkpointDownloadUrl());
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

    @Test
    void refreshDownloadLinkSignsOnlyAnAllowedCandidateOfTheResumeLineage() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = new DispatchCheckpointService(dao,
                mock(DispatchRuntimeEventDao.class), mock(DispatchDao.class), storage, new OssProperties());
        DispatchDO dispatch = resumableDispatch();
        DispatchCheckpointDO latest = checkpoint(2L, "bucket/latest", "latest-sha");
        DispatchCheckpointDO previous = checkpoint(1L, "bucket/previous", "previous-sha");
        when(dao.listLatestByDispatch(100L, 55L, 2)).thenReturn(List.of(latest, previous));
        when(storage.exists("bucket/latest")).thenReturn(true);
        when(storage.exists("bucket/previous")).thenReturn(true);
        when(storage.presignGet("bucket/previous", 600)).thenReturn("https://oss/previous");

        CheckpointDownloadLink link = service.refreshDownloadLink(dispatch, 1L);

        assertEquals(1L, link.checkpointSeq());
        assertEquals("https://oss/previous", link.downloadUrl());
        assertEquals("sha256:previous-sha", link.sha256());
        assertEquals(600, link.expiresInSeconds());
        assertNotNull(link.expiresAt());
        verify(storage).presignGet("bucket/previous", 600);
    }

    @Test
    void refreshDownloadLinkRejectsDispatchThatIsNotAResumeExecution() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = new DispatchCheckpointService(dao,
                mock(DispatchRuntimeEventDao.class), mock(DispatchDao.class), storage, new OssProperties());
        DispatchDO dispatch = new DispatchDO();
        dispatch.setTenantId(100L);
        dispatch.setResumeMode("RECOVERY");

        assertThrows(DispatchCheckpointService.CheckpointNotRestorableException.class,
                () -> service.refreshDownloadLink(dispatch, 1L));
        verifyNoInteractions(storage);
    }

    @Test
    void refreshDownloadLinkRejectsModeWithoutCheckpointRestore() {
        DispatchCheckpointService service = serviceWith(mock(DispatchCheckpointDao.class), mock(ObjectStorage.class));
        DispatchDO dispatch = resumableDispatch();
        dispatch.setResumeMode("RETURNING_WORKER");

        assertThrows(DispatchCheckpointService.CheckpointNotRestorableException.class,
                () -> service.refreshDownloadLink(dispatch, 1L));
    }

    @Test
    void refreshDownloadLinkRejectsInvalidCheckpointIdentity() {
        DispatchCheckpointService service = serviceWith(mock(DispatchCheckpointDao.class), mock(ObjectStorage.class));

        assertThrows(DispatchCheckpointService.CheckpointInvalidRequestException.class,
                () -> service.refreshDownloadLink(resumableDispatch(), 0L));
    }

    @Test
    void refreshDownloadLinkReportsMissingObjectInsteadOfSigningIt() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = serviceWith(dao, storage);
        when(dao.listLatestByDispatch(100L, 55L, 2))
                .thenReturn(List.of(checkpoint(3L, "bucket/gone", "gone-sha")));
        when(storage.exists("bucket/gone")).thenReturn(false);

        assertThrows(DispatchCheckpointService.CheckpointObjectMissingException.class,
                () -> service.refreshDownloadLink(resumableDispatch(), 3L));
        verify(storage, never()).presignGet(anyString(), anyInt());
    }

    @Test
    void refreshDownloadLinkReportsUnknownCheckpointAsNotFound() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = new DispatchCheckpointService(dao,
                mock(DispatchRuntimeEventDao.class), dispatchDao, storage, new OssProperties());

        assertThrows(DispatchCheckpointService.CheckpointNotFoundException.class,
                () -> service.refreshDownloadLink(resumableDispatch(), 9L));
        verify(storage, never()).presignGet(anyString(), anyInt());
    }

    @Test
    void refreshDownloadLinkRejectsLineageCheckpointThatIsNotACandidate() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = new DispatchCheckpointService(
                dao, runtimeEventDao, dispatchDao, storage, new OssProperties());
        DispatchRuntimeEventDO pinned = new DispatchRuntimeEventDO();
        pinned.setDetailJson("{\"sessionId\":\"session-55\",\"provider\":\"codex\"}");
        when(runtimeEventDao.findLatestByDispatchAndType(100L, 55L, "agent.session_pinned"))
                .thenReturn(pinned);
        DispatchDO directSource = new DispatchDO();
        directSource.setId(55L);
        directSource.setTenantId(100L);
        directSource.setResumeFromDispatchId(44L);
        when(dispatchDao.findById(55L)).thenReturn(directSource);
        DispatchCheckpointDO ancestor = checkpoint(7L, "bucket/ancestor", "ancestor-sha");
        when(dao.findByDispatchAndSeq(100L, 44L, 7L)).thenReturn(ancestor);
        when(storage.exists("bucket/ancestor")).thenReturn(true);

        assertThrows(DispatchCheckpointService.CheckpointNotRestorableException.class,
                () -> service.refreshDownloadLink(resumableDispatch(), 7L));
        verify(storage, never()).presignGet(anyString(), anyInt());
    }

    @Test
    void refreshDownloadLinkReportsMissingObjectForPrunedLineageCheckpoint() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = new DispatchCheckpointService(dao,
                mock(DispatchRuntimeEventDao.class), dispatchDao, storage, new OssProperties());
        DispatchDO directSource = new DispatchDO();
        directSource.setId(55L);
        directSource.setTenantId(100L);
        directSource.setResumeFromDispatchId(44L);
        when(dispatchDao.findById(55L)).thenReturn(directSource);
        DispatchCheckpointDO ancestor = checkpoint(7L, "bucket/ancestor", "ancestor-sha");
        when(dao.findByDispatchAndSeq(100L, 44L, 7L)).thenReturn(ancestor);
        when(storage.exists("bucket/ancestor")).thenReturn(false);

        assertThrows(DispatchCheckpointService.CheckpointObjectMissingException.class,
                () -> service.refreshDownloadLink(resumableDispatch(), 7L));
        verify(storage, never()).presignGet(anyString(), anyInt());
    }

    @Test
    void refreshDownloadLinkReportsUnknownCheckpointWhenLineageEnds() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchCheckpointService service = new DispatchCheckpointService(dao,
                mock(DispatchRuntimeEventDao.class), dispatchDao, mock(ObjectStorage.class), new OssProperties());
        DispatchDO terminalSource = new DispatchDO();
        terminalSource.setId(55L);
        terminalSource.setTenantId(100L);
        when(dispatchDao.findById(55L)).thenReturn(terminalSource);

        assertThrows(DispatchCheckpointService.CheckpointNotFoundException.class,
                () -> service.refreshDownloadLink(resumableDispatch(), 9L));
    }

    @Test
    void refreshDownloadLinkKeepsCandidateWhenAvailabilityProbeFails() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = serviceWith(dao, storage);
        when(dao.listLatestByDispatch(100L, 55L, 2))
                .thenReturn(List.of(checkpoint(4L, "bucket/probe", "probe-sha")));
        when(storage.exists("bucket/probe")).thenThrow(new RuntimeException("oss probe failed"));
        when(storage.presignGet("bucket/probe", 600)).thenReturn("https://oss/probe");

        CheckpointDownloadLink link = service.refreshDownloadLink(resumableDispatch(), 4L);

        assertEquals("https://oss/probe", link.downloadUrl());
    }

    @Test
    void refreshDownloadLinkSignsTheCheckpointProjectedByTheLegacyNormalizer() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = serviceWith(dao, storage);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "legacyCheckpointNormalizer",
                new LegacyCheckpointNormalizer(mock(com.aliyun.autowonder.artifact.ArtifactDao.class), storage));
        DispatchCheckpointDO recorded = checkpoint(5L, "bucket/legacy", "legacy-sha");
        when(dao.listLatestByDispatch(100L, 55L, 2)).thenReturn(List.of(recorded));
        when(storage.exists("bucket/legacy")).thenReturn(true);
        when(storage.presignGet("bucket/legacy", 600)).thenReturn("https://oss/legacy");

        CheckpointDownloadLink link = service.refreshDownloadLink(resumableDispatch(), 5L);

        assertEquals("https://oss/legacy", link.downloadUrl());
        assertEquals("sha256:legacy-sha", link.sha256());
    }

    @Test
    void refreshDownloadLinkReportsCheckpointWithoutObjectReference() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = serviceWith(dao, storage);
        DispatchCheckpointDO recorded = checkpoint(6L, " ", "blank-sha");
        when(dao.listLatestByDispatch(100L, 55L, 2)).thenReturn(List.of(recorded));
        when(storage.exists(" ")).thenReturn(true);

        assertThrows(DispatchCheckpointService.CheckpointObjectMissingException.class,
                () -> service.refreshDownloadLink(resumableDispatch(), 6L));
        verify(storage, never()).presignGet(anyString(), anyInt());
    }

    @Test
    void firstPauseReceiptInsertsDurableAcceptanceDecision() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchCheckpointService service = serviceWith(dao, mock(ObjectStorage.class));
        DispatchResultStore store = mock(DispatchResultStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "resultStore", store);
        DispatchCheckpointDO receipt = checkpoint(42L, "bucket/pause", "abc");
        receipt.setExecutorId(7L);
        when(dao.findByDispatchAndSeq(100L, 55L, 42L)).thenReturn(receipt);

        assertTrue(service.confirmPauseReceipt(100, 7, 55, 42, "sha256:abc"));

        var ack = org.mockito.ArgumentCaptor.forClass(com.aliyun.autowonder.json.JSONObject.class);
        verify(store).insert(eq(100L), eq(7L), any(), anyString(), ack.capture(), isNull());
        assertEquals("PAUSE_CONFIRMED", ack.getValue().getString("reasonCode"));
        assertEquals("ACCEPTED", ack.getValue().getString("disposition"));
        verify(store).lockDispatch(100, 55);
    }

    @Test
    void pauseReceiptAcceptsAnExistingMatchingPinAndRejectsMissingOrForeignRows() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchCheckpointService service = serviceWith(dao, mock(ObjectStorage.class));
        DispatchResultStore store = mock(DispatchResultStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "resultStore", store);
        DispatchCheckpointDO pin = checkpoint(42L, "bucket/pin", "abc");
        pin.setExecutorId(7L);
        DispatchCheckpointDO receipt = checkpoint(42L, "bucket/pause", "abc");
        receipt.setExecutorId(7L);
        when(dao.findPauseCheckpoint(100L, 55L, 7L)).thenReturn(pin);
        when(dao.findByDispatchAndSeq(100L, 55L, 42L)).thenReturn(receipt);

        assertTrue(service.confirmPauseReceipt(100, 7, 55, 42, "sha256:abc"));

        when(dao.findPauseCheckpoint(100L, 55L, 7L)).thenReturn(null);
        when(dao.findByDispatchAndSeq(100L, 55L, 43L)).thenReturn(null);
        assertFalse(service.confirmPauseReceipt(100, 7, 55, 43, "sha256:abc"));

        DispatchCheckpointDO foreign = checkpoint(44L, "bucket/foreign", "abc");
        foreign.setExecutorId(9L);
        when(dao.findByDispatchAndSeq(100L, 55L, 44L)).thenReturn(foreign);
        assertFalse(service.confirmPauseReceipt(100, 7, 55, 44, "sha256:abc"));
    }

    @Test
    void pauseReceiptRejectsAReplayedDecisionRecordedForAnotherExecutor() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchCheckpointService service = serviceWith(dao, mock(ObjectStorage.class));
        DispatchResultStore store = mock(DispatchResultStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "resultStore", store);
        String hash = "sha256:abc";
        String identity = "pause:100:7:55:42:" + hash;
        String resultId = java.util.UUID.nameUUIDFromBytes(
                identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        var ack = new com.aliyun.autowonder.json.JSONObject();
        ack.put("reasonCode", "PAUSE_CONFIRMED");
        when(store.find(100, 55, resultId))
                .thenReturn(new DispatchResultStore.Decision(9L, "other-hash", ack, null));

        assertFalse(service.confirmPauseReceipt(100, 7, 55, 42, hash));
    }

    @Test
    void retentionFollowsDurableBoundariesAcrossPausedAndCompletedLineage() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        OssProperties properties = new OssProperties();
        properties.setArtifactBucket("artifact-bucket");
        DispatchCheckpointService service = new DispatchCheckpointService(dao,
                mock(DispatchRuntimeEventDao.class), dispatchDao, storage, properties);
        DispatchDO source = lineageDispatch(55L, DispatchStatus.RUNNING, 400L);
        DispatchDO pausedParent = lineageDispatch(44L, DispatchStatus.PAUSED, 300L);
        DispatchDO doneParent = lineageDispatch(45L, DispatchStatus.SUCCEEDED, 300L);
        doneParent.setExecutorId(9L);
        DispatchDO consumer = lineageDispatch(66L, DispatchStatus.RUNNING, 400L);
        consumer.setResumeFromDispatchId(44L);
        consumer.setDeliverySourceDispatchId(45L);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L))
                .thenReturn(List.of(source, pausedParent, doneParent, consumer));
        DispatchCheckpointDO pin = checkpoint(42L, "bucket/pin", "pin-sha");
        pin.setExecutorId(7L);
        when(dao.findPauseCheckpoint(100L, 44L, 7L)).thenReturn(pin);
        when(dao.findLatestByDispatch(100L, 45L)).thenReturn(null);
        when(storage.put(anyString(), anyString(), any())).thenReturn(new StoredObject("artifact-bucket/new", "md5", 1L));
        DispatchCheckpointDO obsolete = new DispatchCheckpointDO();
        obsolete.setId(1L);
        when(dao.listObsolete(100L, 55L, 2)).thenReturn(List.of(obsolete));

        service.store(source, 3L, "codex", "session", "runtime", "step", new byte[]{1});

        verify(dao).findPauseCheckpoint(100L, 44L, 7L);
        verify(dao).findLatestByDispatch(100L, 45L);
        verify(dao).releaseResolvedPins(100L, 55L);
        verify(dao).markGarbage(100L, 55L, 1L);
    }

    @Test
    void liveLineageReferenceKeepsRetentionFromMarkingAnyRow() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        OssProperties properties = new OssProperties();
        properties.setArtifactBucket("artifact-bucket");
        DispatchCheckpointService service = new DispatchCheckpointService(dao,
                mock(DispatchRuntimeEventDao.class), dispatchDao, storage, properties);
        DispatchDO source = lineageDispatch(55L, DispatchStatus.RUNNING, 400L);
        DispatchDO rework = lineageDispatch(66L, DispatchStatus.RUNNING, 400L);
        rework.setResumeFromDispatchId(55L);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(source, rework));
        when(storage.put(anyString(), anyString(), any())).thenReturn(new StoredObject("artifact-bucket/new", "md5", 1L));

        service.store(source, 3L, "codex", "session", "runtime", "step", new byte[]{1});

        verify(dao, never()).releaseResolvedPins(anyLong(), anyLong());
        verify(dao, never()).listObsolete(anyLong(), anyLong(), anyInt());
        verify(dao, never()).markGarbage(anyLong(), anyLong(), anyLong());
    }

    @Test
    void pausedBoundaryWithoutReliablePinDefersRetentionInsteadOfReleasing() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchCheckpointService service = new DispatchCheckpointService(dao,
                mock(DispatchRuntimeEventDao.class), dispatchDao, mock(ObjectStorage.class), new OssProperties());
        DispatchResultStore store = mock(DispatchResultStore.class);
        when(store.transaction(any())).thenAnswer(call -> ((java.util.function.Supplier<?>)call.getArgument(0)).get());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "resultStore", store);
        DispatchDO source = lineageDispatch(55L, DispatchStatus.PAUSED, 400L);
        DispatchCheckpointDO subject = new DispatchCheckpointDO();
        subject.setId(9L);
        subject.setTenantId(100L);
        subject.setDispatchId(55L);
        when(dao.listRetentionSubjects(2, 200)).thenReturn(List.of(subject));
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(source));
        when(dao.findPauseCheckpoint(100L, 55L, 7L)).thenReturn(null);

        service.reconcileRetention();

        verify(dao, never()).releaseResolvedPins(anyLong(), anyLong());
        verify(dao, never()).listObsolete(anyLong(), anyLong(), anyInt());
    }

    @Test
    void durablePauseReplacementLetsRetentionReclaimSupersededRows() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = new DispatchCheckpointService(dao,
                mock(DispatchRuntimeEventDao.class), dispatchDao, storage, new OssProperties());
        DispatchDO source = lineageDispatch(55L, DispatchStatus.PAUSED, 400L);
        DispatchDO older = lineageDispatch(40L, DispatchStatus.SUCCEEDED, 400L);
        DispatchDO otherAgent = lineageDispatch(60L, DispatchStatus.SUCCEEDED, 999L);
        DispatchDO replacement = lineageDispatch(66L, DispatchStatus.SUCCEEDED, 400L);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L))
                .thenReturn(List.of(source, older, otherAgent, replacement));
        DispatchCheckpointDO boundary = checkpoint(8L, "artifact-bucket/replacement", "replacement-sha");
        boundary.setExecutorId(7L);
        when(dao.findLatestByDispatch(100L, 66L)).thenReturn(boundary);
        when(storage.exists("artifact-bucket/replacement")).thenReturn(true);
        DispatchCheckpointDO pausePin = checkpoint(42L, "bucket/pin", "pin-sha");
        pausePin.setExecutorId(7L);
        when(dao.findPauseCheckpoint(100L, 55L, 7L)).thenReturn(pausePin);
        DispatchCheckpointDO obsolete = new DispatchCheckpointDO();
        obsolete.setId(2L);
        when(dao.listObsolete(100L, 55L, 2)).thenReturn(List.of(obsolete));
        DispatchCheckpointDO subject = new DispatchCheckpointDO();
        subject.setId(9L);
        subject.setTenantId(100L);
        subject.setDispatchId(55L);
        DispatchResultStore store = mock(DispatchResultStore.class);
        when(store.transaction(any())).thenAnswer(call -> ((java.util.function.Supplier<?>)call.getArgument(0)).get());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "resultStore", store);
        when(dao.listRetentionSubjects(2, 200)).thenReturn(List.of(subject));
        when(dispatchDao.findById(55L)).thenReturn(source);

        service.reconcileRetention();

        verify(storage).exists("artifact-bucket/replacement");
        verify(dao).markGarbage(100L, 55L, 2L);
    }

    @Test
    void retentionReconciliationRestoresReferencedGarbageBeforeDeletingObjects() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = new DispatchCheckpointService(dao,
                mock(DispatchRuntimeEventDao.class), dispatchDao, storage, new OssProperties());
        DispatchResultStore store = mock(DispatchResultStore.class);
        when(store.transaction(any())).thenAnswer(call -> ((java.util.function.Supplier<?>)call.getArgument(0)).get());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "resultStore", store);
        DispatchDO live = lineageDispatch(55L, DispatchStatus.RUNNING, 400L);
        DispatchCheckpointDO pending = new DispatchCheckpointDO();
        pending.setId(1L);
        pending.setTenantId(100L);
        pending.setDispatchId(55L);
        pending.setOssRef("artifact-bucket/garbage");
        when(dao.listGarbage(200)).thenReturn(List.of(pending));
        when(dispatchDao.findById(55L)).thenReturn(live);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(live));
        when(dao.restoreReferencedGarbage(100L, 55L, 1L)).thenReturn(1);

        service.reconcileRetention();

        verify(dao).restoreReferencedGarbage(100L, 55L, 1L);
        verify(dao, never()).restoreGarbage(anyLong(), anyLong(), anyLong());
        verify(storage, never()).delete(anyString());
        verify(dao, never()).deleteById(anyLong(), anyLong(), anyLong());
    }

    @Test
    void pauseReceiptRequiresTheDurableResultStore() {
        DispatchCheckpointService service = serviceWith(mock(DispatchCheckpointDao.class), mock(ObjectStorage.class));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.confirmPauseReceipt(100, 7, 55, 42, "sha256:abc"));

        assertEquals("RESULT_SCHEMA_NOT_READY", failure.getMessage());
    }

    @Test
    void missingLineageRowsKeepEveryCheckpointReferenced() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = lineageService(dao, dispatchDao, storage);
        DispatchDO source = lineageDispatch(55L, DispatchStatus.RUNNING, 400L);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(null);
        when(dao.findByDispatchAndSeq(100L, 55L, 3L)).thenReturn(null);
        when(storage.put(anyString(), anyString(), any())).thenReturn(new StoredObject("artifact-bucket/new", "md5", 1L));

        service.store(source, 3L, "codex", "session", "runtime", "step", new byte[]{1});

        verify(dao, never()).releaseResolvedPins(anyLong(), anyLong());
        verify(dao, never()).listObsolete(anyLong(), anyLong(), anyInt());
    }

    @Test
    void unknownLineageParentKeepsEveryCheckpointReferenced() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = lineageService(dao, dispatchDao, storage);
        DispatchDO source = lineageDispatch(55L, DispatchStatus.RUNNING, 400L);
        DispatchDO consumer = lineageDispatch(66L, DispatchStatus.RUNNING, 400L);
        consumer.setResumeFromDispatchId(77L);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(source, consumer));
        when(dao.findByDispatchAndSeq(100L, 55L, 3L)).thenReturn(null);
        when(storage.put(anyString(), anyString(), any())).thenReturn(new StoredObject("artifact-bucket/new", "md5", 1L));

        service.store(source, 3L, "codex", "session", "runtime", "step", new byte[]{1});

        verify(dao, never()).releaseResolvedPins(anyLong(), anyLong());
    }

    @Test
    void cyclicLineageWithoutDurableBoundaryKeepsEveryCheckpointReferenced() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = lineageService(dao, dispatchDao, storage);
        DispatchDO source = lineageDispatch(55L, DispatchStatus.RUNNING, 400L);
        DispatchDO firstHop = lineageDispatch(44L, DispatchStatus.SUCCEEDED, 400L);
        firstHop.setResumeFromDispatchId(45L);
        DispatchDO secondHop = lineageDispatch(45L, DispatchStatus.SUCCEEDED, 400L);
        secondHop.setResumeFromDispatchId(44L);
        DispatchDO consumer = lineageDispatch(66L, DispatchStatus.RUNNING, 400L);
        consumer.setResumeFromDispatchId(44L);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L))
                .thenReturn(List.of(source, firstHop, secondHop, consumer));
        when(dao.findLatestByDispatch(100L, 44L)).thenReturn(null);
        when(dao.findLatestByDispatch(100L, 45L)).thenReturn(null);
        when(dao.findByDispatchAndSeq(100L, 55L, 3L)).thenReturn(null);
        when(storage.put(anyString(), anyString(), any())).thenReturn(new StoredObject("artifact-bucket/new", "md5", 1L));

        service.store(source, 3L, "codex", "session", "runtime", "step", new byte[]{1});

        verify(dao).findLatestByDispatch(100L, 44L);
        verify(dao).findLatestByDispatch(100L, 45L);
        verify(dao).releaseResolvedPins(100L, 55L);
    }

    @Test
    void pausedBoundaryKeepsThePinnedAndLatestRowsWhileCollectingTheRest() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchCheckpointService service = lineageService(dao, dispatchDao, mock(ObjectStorage.class));
        DispatchDO source = lineageDispatch(55L, DispatchStatus.PAUSED, 400L);
        DispatchCheckpointDO pin = checkpoint(42L, "bucket/pin", "pin-sha");
        pin.setId(1L);
        pin.setExecutorId(7L);
        DispatchCheckpointDO latest = checkpoint(41L, "bucket/latest", "latest-sha");
        latest.setId(2L);
        DispatchCheckpointDO obsolete = checkpoint(40L, "bucket/obsolete", "obsolete-sha");
        obsolete.setId(3L);
        DispatchResultStore store = retentionStore(service);
        when(dao.listRetentionSubjects(2, 200)).thenReturn(List.of(retentionSubject(55L)));
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(source));
        when(dao.findPauseCheckpoint(100L, 55L, 7L)).thenReturn(pin);
        when(dao.listLatestByDispatch(100L, 55L, 2)).thenReturn(List.of(latest, pin));
        when(dao.listObsolete(100L, 55L, 2)).thenReturn(List.of(pin, latest, obsolete));

        service.reconcileRetention();

        verify(store, org.mockito.Mockito.atLeastOnce()).transaction(any());
        verify(dao).markGarbage(100L, 55L, 3L);
        verify(dao, never()).markGarbage(100L, 55L, 1L);
        verify(dao, never()).markGarbage(100L, 55L, 2L);
    }

    @Test
    void canceledPauseBoundaryWithoutReplacementStaysProtected() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchCheckpointService service = lineageService(dao, dispatchDao, mock(ObjectStorage.class));
        DispatchDO source = lineageDispatch(55L, DispatchStatus.CANCELED, 400L);
        retentionStore(service);
        when(dao.listRetentionSubjects(2, 200)).thenReturn(List.of(retentionSubject(55L)));
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(source));
        when(dao.hasPauseDecision(100L, 55L, 7L)).thenReturn(1);
        when(dao.findPauseCheckpoint(100L, 55L, 7L)).thenReturn(null);

        service.reconcileRetention();

        verify(dao).hasPauseDecision(100L, 55L, 7L);
        verify(dao, never()).releaseResolvedPins(anyLong(), anyLong());
        verify(dao, never()).listObsolete(anyLong(), anyLong(), anyInt());
    }

    @Test
    void retentionReconciliationSkipsSubjectsWhoseDispatchDisappeared() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = lineageService(dao, dispatchDao, storage);
        retentionStore(service);
        DispatchCheckpointDO pending = new DispatchCheckpointDO();
        pending.setId(1L);
        pending.setTenantId(100L);
        pending.setDispatchId(55L);
        pending.setOssRef("artifact-bucket/garbage");
        when(dao.listRetentionSubjects(2, 200)).thenReturn(List.of(retentionSubject(55L)));
        when(dao.listGarbage(200)).thenReturn(List.of(pending));
        when(dispatchDao.findById(55L)).thenReturn(null);
        when(dao.restoreReferencedGarbage(100L, 55L, 1L)).thenReturn(0);

        service.reconcileRetention();

        verify(dao, never()).restoreGarbage(anyLong(), anyLong(), anyLong());
        verify(storage, never()).delete(anyString());
        verify(dao, never()).deleteById(anyLong(), anyLong(), anyLong());
    }

    @Test
    void pausedDispatchWithoutPinnedBoundaryStillStoresTheFrozenCheckpoint() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = lineageService(dao, dispatchDao, storage);
        DispatchDO source = lineageDispatch(55L, DispatchStatus.PAUSED, 400L);
        when(dao.findByDispatchAndSeq(100L, 55L, 3L)).thenReturn(null);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(source));
        when(storage.put(anyString(), anyString(), any())).thenReturn(new StoredObject("artifact-bucket/new", "md5", 1L));

        DispatchCheckpointDO stored = service.store(source, 3L, "codex", "session", "runtime", "step", new byte[]{1});

        assertEquals(3L, stored.getCheckpointSeq());
        verify(dao).insert(any(DispatchCheckpointDO.class));
    }

    @Test
    void garbageCollectionRestoresPausedRowsThatHaveNoReplacement() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = lineageService(dao, dispatchDao, storage);
        retentionStore(service);
        DispatchDO source = lineageDispatch(55L, DispatchStatus.PAUSED, 400L);
        DispatchCheckpointDO pending = new DispatchCheckpointDO();
        pending.setId(1L);
        pending.setTenantId(100L);
        pending.setDispatchId(55L);
        pending.setOssRef("artifact-bucket/garbage");
        when(dao.listGarbage(200)).thenReturn(List.of(pending));
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(source));
        when(dao.restoreGarbage(100L, 55L, 1L)).thenReturn(1);

        service.reconcileRetention();

        verify(dao).restoreGarbage(100L, 55L, 1L);
        verify(storage, never()).delete(anyString());
        verify(dao, never()).deleteById(anyLong(), anyLong(), anyLong());
    }

    @Test
    void pausedSuccessorWithAStoredBoundaryCountsAsADurableReplacement() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DispatchCheckpointService service = lineageService(dao, dispatchDao, storage);
        retentionStore(service);
        DispatchDO source = lineageDispatch(55L, DispatchStatus.PAUSED, 400L);
        DispatchDO successor = lineageDispatch(66L, DispatchStatus.PAUSED, 400L);
        when(dao.listRetentionSubjects(2, 200)).thenReturn(List.of(retentionSubject(55L)));
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(source, successor));
        DispatchCheckpointDO boundary = checkpoint(9L, "artifact-bucket/successor", "successor-sha");
        boundary.setExecutorId(7L);
        when(dao.findPauseCheckpoint(100L, 66L, 7L)).thenReturn(boundary);
        when(storage.exists("artifact-bucket/successor")).thenReturn(true);
        when(dao.listObsolete(100L, 55L, 2)).thenReturn(List.of());

        service.reconcileRetention();

        verify(storage).exists("artifact-bucket/successor");
    }

    private static DispatchCheckpointService lineageService(DispatchCheckpointDao dao,
            DispatchDao dispatchDao, ObjectStorage storage) {
        OssProperties properties = new OssProperties();
        properties.setArtifactBucket("artifact-bucket");
        return new DispatchCheckpointService(dao, mock(DispatchRuntimeEventDao.class), dispatchDao,
                storage, properties);
    }

    private static DispatchResultStore retentionStore(DispatchCheckpointService service) {
        DispatchResultStore store = mock(DispatchResultStore.class);
        when(store.transaction(any())).thenAnswer(call -> ((java.util.function.Supplier<?>)call.getArgument(0)).get());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "resultStore", store);
        return store;
    }

    private static DispatchCheckpointDO retentionSubject(long dispatchId) {
        DispatchCheckpointDO subject = new DispatchCheckpointDO();
        subject.setId(9L);
        subject.setTenantId(100L);
        subject.setDispatchId(dispatchId);
        return subject;
    }

    private static DispatchDO lineageDispatch(long id, String status, long agentId) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(100L);
        dispatch.setWorkitemId(200L);
        dispatch.setAgentId(agentId);
        dispatch.setExecutorId(7L);
        dispatch.setStatus(status);
        dispatch.setSourceType(ExecutionSourceType.WORKITEM.name());
        return dispatch;
    }

    private static DispatchCheckpointService serviceWith(DispatchCheckpointDao dao, ObjectStorage storage) {
        return new DispatchCheckpointService(dao, mock(DispatchRuntimeEventDao.class),
                mock(DispatchDao.class), storage, new OssProperties());
    }

    private static DispatchDO resumableDispatch() {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(77L);
        dispatch.setTenantId(100L);
        dispatch.setResumeMode("RECOVERY");
        dispatch.setResumeFromDispatchId(55L);
        return dispatch;
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
