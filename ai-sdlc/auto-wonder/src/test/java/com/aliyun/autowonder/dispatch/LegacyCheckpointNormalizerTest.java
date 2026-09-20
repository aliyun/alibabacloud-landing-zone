package com.aliyun.autowonder.dispatch;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyCheckpointNormalizerTest {
    static final String PREFIX = "t/10002/workitem/55411/dispatch/13378/";
    static final String ACCEPTED = "artifacts/accepted/publish-manifest.json";
    static final String ATTEMPT = "artifacts/attempts/400170-hash/attempt-1/publish-manifest.json";
    static final String PATH = "evidence/code-review-result.md";
    final ArtifactDao artifacts = mock(ArtifactDao.class);
    final InMemoryObjectStorage storage = new InMemoryObjectStorage();
    final byte[] content = "verified review result".getBytes(StandardCharsets.UTF_8);

    @Test void projectsVerifiedLegacyReferencesWithoutChangingOriginalOrReceiptIdentity() throws Exception {
        DispatchCheckpointDO source = source(PREFIX);
        ArtifactDO artifact = artifact();
        when(artifacts.listByDispatch(10002L, 13378L)).thenReturn(List.of(artifact));
        byte[] original = storage.get(source.getOssRef());
        LegacyCheckpointNormalizer normalizer = new LegacyCheckpointNormalizer(artifacts, storage);

        DispatchCheckpointDO projected = normalizer.normalize(source);

        assertNotEquals(source.getOssRef(), projected.getOssRef());
        assertArrayEquals(original, storage.get(source.getOssRef()));
        assertEquals(sha(original), source.getSha256());
        byte[] compatible = storage.get(projected.getOssRef());
        assertEquals(sha(compatible), projected.getSha256());
        assertEquals(source.getCheckpointSeq(), projected.getCheckpointSeq());
        Map<String, byte[]> before = unpack(original), after = unpack(compatible);
        assertEquals(before.keySet(), after.keySet());
        for (String name : List.of(ACCEPTED, ATTEMPT)) {
            JSONObject entry = JSON.parseObject(new String(after.get(name), StandardCharsets.UTF_8))
                    .getJSONArray("entries").getJSONObject(0);
            assertEquals(artifact.getOssRef(), entry.getString("remoteRef"));
            assertEquals("reference_only", entry.getString("disposition"));
        }
        for (String name : before.keySet()) {
            if (!List.of(ACCEPTED, ATTEMPT).contains(name)) assertArrayEquals(before.get(name), after.get(name), name);
        }
        assertEquals(projected.getSha256(), normalizer.normalize(source).getSha256());
        assertEquals(projected.getOssRef(), normalizer.normalize(source).getOssRef());
    }

    @Test void validExactReferencesAreReturnedUnchangedWithoutArtifactLookups() throws Exception {
        DispatchCheckpointDO source = source("bucket/" + PREFIX + "artifacts/output/" + PATH);
        assertSame(source, new LegacyCheckpointNormalizer(artifacts, storage).normalize(source));
        verifyNoInteractions(artifacts);
    }

    @Test void missingOrUnverifiableArtifactsKeepOriginalCandidate() throws Exception {
        DispatchCheckpointDO source = source(PREFIX);
        ArtifactDO row = artifact();
        when(artifacts.listByDispatch(10002L, 13378L)).thenReturn(List.of(row));
        LegacyCheckpointNormalizer normalizer = new LegacyCheckpointNormalizer(artifacts, storage);
        storage.delete(row.getOssRef());
        assertSame(source, normalizer.normalize(source));
        storage.put("bucket", PREFIX + row.getName(), bytes("corrupt"));
        assertSame(source, normalizer.normalize(source));
        byte[] wrongDigest = content.clone(); wrongDigest[0] ^= 1;
        storage.put("bucket", PREFIX + row.getName(), wrongDigest);
        assertSame(source, normalizer.normalize(source));
        storage.put("bucket", PREFIX + row.getName(), content);
        row.setWorkitemId(55412L);
        assertSame(source, normalizer.normalize(source));
        row.setWorkitemId(55411L); row.setSourceType("CHAT");
        assertSame(source, normalizer.normalize(source));
        row.setSourceType("WORKITEM"); row.setOssRef("bucket/t/10002/workitem/55412/dispatch/13378/" + row.getName());
        assertSame(source, normalizer.normalize(source));
    }

    @Test void conflictingCompatibilityObjectIsNotOverwritten() throws Exception {
        DispatchCheckpointDO source = source(PREFIX);
        when(artifacts.listByDispatch(10002L, 13378L)).thenReturn(List.of(artifact()));
        String key = PREFIX + "checkpoint.tar.gz.compat-exact-ref-v1.tar.gz";
        byte[] conflict = bytes("unrelated existing bytes");
        storage.put("bucket", key, conflict);
        assertSame(source, new LegacyCheckpointNormalizer(artifacts, storage).normalize(source));
        assertArrayEquals(conflict, storage.get("bucket/" + key));
    }

    @Test void repairPreservesUnrelatedPayloadBytesAndExecutableMode() throws Exception {
        DispatchCheckpointDO source = source(PREFIX);
        when(artifacts.listByDispatch(10002L, 13378L)).thenReturn(List.of(artifact()));
        Map<String, byte[]> members = unpack(storage.get(source.getOssRef()));
        members.put("repos/example/run.sh", bytes("#!/bin/sh\nexit 0\n"));
        byte[] original = pack(members);
        storage.put("bucket", PREFIX + "checkpoint.tar.gz", original);
        source.setSizeBytes((long) original.length); source.setSha256(sha(original));

        DispatchCheckpointDO result = new LegacyCheckpointNormalizer(artifacts, storage).normalize(source);

        assertNotSame(source, result);
        byte[] compatible = storage.get(result.getOssRef());
        assertArrayEquals(members.get("repos/example/run.sh"), unpack(compatible).get("repos/example/run.sh"));
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(new ByteArrayInputStream(compatible)))) {
            for (TarArchiveEntry entry; (entry = tar.getNextTarEntry()) != null;) {
                if (entry.getName().equals("repos/example/run.sh")) { assertEquals(0755, entry.getMode()); return; }
            }
        }
        fail("unrelated executable missing from compatibility archive");
    }

    @Test void foreignPrefixAndAmbiguousArtifactRowsAreNotGuessed() throws Exception {
        DispatchCheckpointDO foreign = source("t/10002/workitem/55411/dispatch/13379/");
        LegacyCheckpointNormalizer normalizer = new LegacyCheckpointNormalizer(artifacts, storage);
        assertSame(foreign, normalizer.normalize(foreign));
        DispatchCheckpointDO source = source(PREFIX);
        when(artifacts.listByDispatch(10002L, 13378L)).thenReturn(List.of(artifact(), artifact()));
        assertSame(source, normalizer.normalize(source));
    }

    @Test void corruptArchiveOrWrongSourceIdentityCannotBeNormalized() throws Exception {
        DispatchCheckpointDO source = source(PREFIX);
        when(artifacts.listByDispatch(10002L, 13378L)).thenReturn(List.of(artifact()));
        LegacyCheckpointNormalizer normalizer = new LegacyCheckpointNormalizer(artifacts, storage);
        String checksum = source.getSha256(); source.setSha256("0".repeat(64));
        assertSame(source, normalizer.normalize(source));
        source.setSha256(checksum); source.setDispatchId(13379L);
        assertSame(source, normalizer.normalize(source));
        source.setDispatchId(13378L); source.setCheckpointSeq(124L);
        assertSame(source, normalizer.normalize(source));
    }

    @Test void unsupportedJournalAndUnsafeArchiveMembersKeepOriginal() throws Exception {
        for (String member : List.of("state/accepted-publication-pending.json", "../outside", "/absolute")) {
            DispatchCheckpointDO source = source(PREFIX);
            Map<String, byte[]> members = unpack(storage.get(source.getOssRef()));
            members.put(member, bytes("{}"));
            byte[] archive = pack(members);
            storage.put("bucket", PREFIX + "checkpoint.tar.gz", archive);
            source.setSha256(sha(archive)); source.setSizeBytes((long) archive.length);
            when(artifacts.listByDispatch(10002L, 13378L)).thenReturn(List.of(artifact()));
            assertSame(source, new LegacyCheckpointNormalizer(artifacts, storage).normalize(source), member);
        }
    }

    DispatchCheckpointDO source(String ref) throws Exception {
        JSONObject entry = new JSONObject(true);
        entry.put("path", PATH); entry.put("sha256", "sha256:" + sha(content));
        entry.put("sizeBytes", content.length); entry.put("disposition", "reference_only"); entry.put("remoteRef", ref);
        JSONObject manifest = new JSONObject(true);
        manifest.put("schemaVersion", "autowonder.publishManifest.v1"); manifest.put("entries", List.of(entry));
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("checkpoint.json", bytes("{\"schemaVersion\":\"autowonder.runtimeCheckpoint.v2\",\"dispatchId\":\"13378\",\"checkpointSeq\":123,\"stateSha256\":\"producer-fingerprint\"}"));
        files.put(ACCEPTED, bytes(manifest.toJSONString())); files.put(ATTEMPT, bytes(manifest.toJSONString()));
        files.put("publication-payload-map.json", bytes("{\"schemaVersion\":\"autowonder.publicationPayloadMap.v1\",\"entries\":null}"));
        files.put("state/artifact-receipts.json", bytes("{\"identity\":{\"tenantId\":\"10002\",\"workitemId\":\"55411\",\"dispatchId\":\"13378\",\"attempt\":1}}"));
        files.put("state/sdlc-progress.json", bytes("{\"completedStepIds\":[\"400169\"]}"));
        byte[] archive = pack(files);
        DispatchCheckpointDO cp = new DispatchCheckpointDO(); cp.setId(51256L);
        cp.setTenantId(10002L); cp.setWorkitemId(55411L); cp.setDispatchId(13378L); cp.setAgentId(40014L);
        cp.setCheckpointSeq(123L); cp.setSha256(sha(archive)); cp.setSizeBytes((long) archive.length);
        cp.setOssRef(storage.put("bucket", PREFIX + "checkpoint.tar.gz", archive).getOssRef());
        return cp;
    }

    ArtifactDO artifact() {
        ArtifactDO row = new ArtifactDO(); row.setTenantId(10002L); row.setWorkitemId(55411L);
        row.setDispatchId(13378L); row.setName("artifacts/output/" + PATH); row.setSize((long) content.length);
        row.setOssRef(storage.put("bucket", PREFIX + row.getName(), content).getOssRef()); return row;
    }

    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static String sha(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
    static byte[] pack(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(out))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (var file : files.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey(), true); entry.setModTime(0); entry.setSize(file.getValue().length);
                if (file.getKey().endsWith("run.sh")) entry.setMode(0755);
                tar.putArchiveEntry(entry); tar.write(file.getValue()); tar.closeArchiveEntry();
            }
        }
        return out.toByteArray();
    }
    static Map<String, byte[]> unpack(byte[] data) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            for (TarArchiveEntry entry; (entry = tar.getNextTarEntry()) != null;) result.put(entry.getName(), tar.readAllBytes());
        }
        return result;
    }
}
