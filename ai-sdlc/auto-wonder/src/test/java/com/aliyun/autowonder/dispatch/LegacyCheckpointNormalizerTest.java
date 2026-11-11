package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
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

    @Test void restoresOldProducerControlBytesForAcceptedAndInheritedAttempts() throws Exception {
        DispatchCheckpointDO source = historicalHandoff();
        ArtifactDO current = handoffArtifact(13378L, bytes("{\"nextOwner\":\"terraform-qa\",\"nextRole\":\"terraform-qa\"}"));
        ArtifactDO previous = handoffArtifact(13370L, bytes("{\"nextOwner\":\"original-reviewer\",\"nextRole\":\"review\"}"));
        bindHandoff(source, previous, ACCEPTED);
        bindHandoff(source, current, ATTEMPT);
        when(artifacts.listByWorkitem(10002L, 55411L)).thenReturn(List.of(current, previous));
        byte[] original = storage.get(source.getOssRef());
        Map<String, byte[]> before = unpack(original);
        // Old v1 compatibility objects must not collide with the new projection.
        storage.put("bucket", LegacyCheckpointNormalizer.legacyCompatibilityRef(source.getOssRef()).substring(7), bytes("old projection"));
        LegacyCheckpointNormalizer normalizer = new LegacyCheckpointNormalizer(artifacts, storage);
        DispatchCheckpointDO projected = normalizer.normalize(source);

        assertNotSame(source, projected);
        assertArrayEquals(original, storage.get(source.getOssRef()));
        Map<String, byte[]> restored = unpack(storage.get(projected.getOssRef()));
        JSONObject payloadMap = JSON.parseObject(new String(restored.get("publication-payload-map.json"), StandardCharsets.UTF_8));
        assertEquals(2, payloadMap.getJSONArray("entries").size());
        for (String path : List.of(ACCEPTED, ATTEMPT)) {
            JSONObject control = JSON.parseObject(new String(restored.get(path), StandardCharsets.UTF_8)).getJSONArray("entries").getJSONObject(0);
            assertEquals("upload", control.getString("disposition"));
            assertFalse(control.containsKey("remoteRef"));
            String hash = control.getString("sha256").substring(7);
            assertEquals(hash, sha(restored.get("publication-payloads/" + hash)));
        }
        assertArrayEquals(before.get("checkpoint.json"), restored.get("checkpoint.json"));
        assertEquals(projected.getSha256(), normalizer.normalize(source).getSha256());
        assertEquals(sha(storage.get(projected.getOssRef())), projected.getSha256());
    }

    @Test void restoresLegacyFlatHandoffControlReference() throws Exception {
        ArtifactDO row = legacyHandoffArtifact(13378L, bytes("{\"nextOwner\":\"terraform-qa\",\"nextRole\":\"terraform-qa\"}"));
        DispatchCheckpointDO source = historicalHandoff();
        bindHandoff(source, row, ACCEPTED);
        when(artifacts.listByWorkitem(10002L, 55411L)).thenReturn(List.of(row));
        DispatchCheckpointDO projected = new LegacyCheckpointNormalizer(artifacts, storage).normalize(source);

        assertNotSame(source, projected);
        JSONObject control = JSON.parseObject(new String(unpack(storage.get(projected.getOssRef())).get(ACCEPTED), StandardCharsets.UTF_8))
                .getJSONArray("entries").getJSONObject(0);
        assertEquals("upload", control.getString("disposition"));
        assertFalse(control.containsKey("remoteRef"));
    }

    @Test void contentAddressedKeyMustBindManifestDigest() throws Exception {
        byte[] payload = bytes("{\"nextOwner\":\"terraform-qa\",\"nextRole\":\"terraform-qa\"}");
        ArtifactDO row = handoffArtifact(13378L, payload);
        String ref = row.getOssRef(), tampered = ref.replace(sha(payload), "0".repeat(64));
        storage.put("bucket", tampered.substring("bucket/".length()), payload);
        storage.delete(ref);
        row.setOssRef(tampered);
        DispatchCheckpointDO source = historicalHandoff();
        bindHandoff(source, row, ACCEPTED);
        when(artifacts.listByWorkitem(10002L, 55411L)).thenReturn(List.of(row));

        assertSame(source, new LegacyCheckpointNormalizer(artifacts, storage).normalize(source));
        assertFalse(storage.exists(LegacyCheckpointNormalizer.compatibilityRef(source.getOssRef())));
    }

    @Test void acceptsLegacyFlatArtifactReferences() throws Exception {
        DispatchCheckpointDO source = source(PREFIX);
        ArtifactDO row = legacyArtifact();
        when(artifacts.listByDispatch(10002L, 13378L)).thenReturn(List.of(row));
        DispatchCheckpointDO projected = new LegacyCheckpointNormalizer(artifacts, storage).normalize(source);

        assertNotSame(source, projected);
        Map<String, byte[]> after = unpack(storage.get(projected.getOssRef()));
        for (String name : List.of(ACCEPTED, ATTEMPT)) {
            JSONObject entry = JSON.parseObject(new String(after.get(name), StandardCharsets.UTF_8))
                    .getJSONArray("entries").getJSONObject(0);
            assertEquals(row.getOssRef(), entry.getString("remoteRef"));
        }
    }

    @Test void skipsNonHandoffAndAlreadyUploadedEntries() throws Exception {
        byte[] payload = bytes("{\"nextOwner\":\"terraform-qa\",\"nextRole\":\"terraform-qa\"}");
        ArtifactDO row = handoffArtifact(13378L, payload);
        DispatchCheckpointDO source = historicalHandoff();
        setEntries(source, ACCEPTED, List.of(
                manifestEntry("handoff/metadata.json", "reference_only", row.getOssRef(), payload),
                manifestEntry("evidence/code-review-result.md", "reference_only", "oss://elsewhere/review.md", content)));
        setEntries(source, ATTEMPT, List.of(manifestEntry("handoff/metadata.json", "upload", null, payload)));
        when(artifacts.listByWorkitem(10002L, 55411L)).thenReturn(List.of(row));

        DispatchCheckpointDO projected = new LegacyCheckpointNormalizer(artifacts, storage).normalize(source);

        assertNotSame(source, projected);
        Map<String, byte[]> restored = unpack(storage.get(projected.getOssRef()));
        JSONObject accepted = JSON.parseObject(new String(restored.get(ACCEPTED), StandardCharsets.UTF_8));
        assertEquals(2, accepted.getJSONArray("entries").size());
        assertEquals("upload", accepted.getJSONArray("entries").getJSONObject(0).getString("disposition"));
        assertEquals("reference_only", accepted.getJSONArray("entries").getJSONObject(1).getString("disposition"));
        assertEquals("upload", JSON.parseObject(new String(restored.get(ATTEMPT), StandardCharsets.UTF_8))
                .getJSONArray("entries").getJSONObject(0).getString("disposition"));
    }

    @Test void manifestWithoutEntriesIsSkipped() throws Exception {
        byte[] payload = bytes("{\"nextOwner\":\"terraform-qa\",\"nextRole\":\"terraform-qa\"}");
        ArtifactDO row = handoffArtifact(13378L, payload);
        DispatchCheckpointDO source = historicalHandoff();
        bindHandoff(source, row, ACCEPTED);
        Map<String, byte[]> members = unpack(storage.get(source.getOssRef()));
        members.put("artifacts/attempts/400171-other/attempt-2/publish-manifest.json",
                bytes("{\"schemaVersion\":\"autowonder.publishManifest.v1\",\"entries\":null}"));
        replaceSource(source, members);
        when(artifacts.listByWorkitem(10002L, 55411L)).thenReturn(List.of(row));

        DispatchCheckpointDO projected = new LegacyCheckpointNormalizer(artifacts, storage).normalize(source);

        assertNotSame(source, projected);
        JSONObject control = JSON.parseObject(new String(unpack(storage.get(projected.getOssRef())).get(ACCEPTED), StandardCharsets.UTF_8))
                .getJSONArray("entries").getJSONObject(0);
        assertEquals("upload", control.getString("disposition"));
    }

    @Test void deduplicatesSameHandoffDigestAcrossManifests() throws Exception {
        byte[] payload = bytes("{\"nextOwner\":\"terraform-qa\",\"nextRole\":\"terraform-qa\"}");
        ArtifactDO row = handoffArtifact(13378L, payload);
        DispatchCheckpointDO source = historicalHandoff();
        bindHandoff(source, row, ACCEPTED);
        bindHandoff(source, row, ATTEMPT);
        when(artifacts.listByWorkitem(10002L, 55411L)).thenReturn(List.of(row));

        DispatchCheckpointDO projected = new LegacyCheckpointNormalizer(artifacts, storage).normalize(source);

        assertNotSame(source, projected);
        String payloadName = "publication-payloads/" + sha(payload);
        Map<String, byte[]> restored = unpack(storage.get(projected.getOssRef()));
        assertArrayEquals(payload, restored.get(payloadName));
        JSONObject payloadMap = JSON.parseObject(new String(restored.get("publication-payload-map.json"), StandardCharsets.UTF_8));
        assertEquals(2, payloadMap.getJSONArray("entries").size());
        for (Object mapping : payloadMap.getJSONArray("entries")) {
            assertEquals(payloadName, ((JSONObject) mapping).getString("payload"));
        }
    }

    @Test void invalidSizeOrDigestKeepsOriginal() throws Exception {
        byte[] payload = bytes("{\"nextOwner\":\"terraform-qa\",\"nextRole\":\"terraform-qa\"}");
        for (String failure : List.of("size-null", "size-negative", "digest-null", "digest-format")) {
            ArtifactDO row = handoffArtifact(13378L, payload);
            DispatchCheckpointDO source = historicalHandoff();
            bindHandoff(source, row, ACCEPTED);
            switch (failure) {
                case "size-null" -> setEntryField(source, ACCEPTED, "sizeBytes", null);
                case "size-negative" -> setEntryField(source, ACCEPTED, "sizeBytes", -1L);
                case "digest-null" -> setEntryField(source, ACCEPTED, "sha256", null);
                case "digest-format" -> setEntryField(source, ACCEPTED, "sha256", "sha256:zz");
            }
            when(artifacts.listByWorkitem(10002L, 55411L)).thenReturn(List.of(row));
            assertSame(source, new LegacyCheckpointNormalizer(artifacts, storage).normalize(source), failure);
            assertFalse(storage.exists(LegacyCheckpointNormalizer.compatibilityRef(source.getOssRef())), failure);
        }
    }

    @Test void missingRowsOrInvalidArtifactKeyKeepOriginal() throws Exception {
        byte[] payload = bytes("{\"nextOwner\":\"terraform-qa\",\"nextRole\":\"terraform-qa\"}");

        DispatchCheckpointDO noRows = historicalHandoff();
        bindHandoff(noRows, handoffArtifact(13378L, payload), ACCEPTED);
        when(artifacts.listByWorkitem(10002L, 55411L)).thenReturn(null);
        assertSame(noRows, new LegacyCheckpointNormalizer(artifacts, storage).normalize(noRows));
        reset(artifacts);

        DispatchCheckpointDO nullRef = historicalHandoff();
        setEntries(nullRef, ACCEPTED, List.of(manifestEntry("handoff/metadata.json", "reference_only", null, payload)));
        when(artifacts.listByWorkitem(10002L, 55411L))
                .thenReturn(List.of(artifactRow(13378L, "artifacts/output/handoff/metadata.json", null, payload.length)));
        assertSame(nullRef, new LegacyCheckpointNormalizer(artifacts, storage).normalize(nullRef));
        reset(artifacts);

        for (String failure : List.of("dispatch", "name", "bucket", "bucket-format")) {
            DispatchCheckpointDO source = historicalHandoff();
            ArtifactDO row = handoffArtifact(13378L, payload);
            String ref = row.getOssRef();
            if (failure.equals("bucket")) ref = "no-bucket-key";
            if (failure.equals("bucket-format")) ref = "bad bucket/" + PREFIX + "artifacts/output/handoff/metadata.json";
            row.setOssRef(ref);
            setEntries(source, ACCEPTED, List.of(manifestEntry("handoff/metadata.json", "reference_only", ref, payload)));
            switch (failure) {
                case "dispatch" -> row.setDispatchId(null);
                case "name" -> row.setName("../metadata.json");
            }
            when(artifacts.listByWorkitem(10002L, 55411L)).thenReturn(List.of(row));
            assertSame(source, new LegacyCheckpointNormalizer(artifacts, storage).normalize(source), failure);
            assertFalse(storage.exists(LegacyCheckpointNormalizer.compatibilityRef(source.getOssRef())), failure);
        }
    }

    @Test void payloadMapEntriesArePreservedAndDuplicateTargetsRejected() throws Exception {
        byte[] payload = bytes("{\"nextOwner\":\"terraform-qa\",\"nextRole\":\"terraform-qa\"}");
        ArtifactDO row = handoffArtifact(13378L, payload);

        DispatchCheckpointDO source = historicalHandoff();
        bindHandoff(source, row, ACCEPTED);
        JSONObject existing = new JSONObject(true);
        existing.put("target", "artifacts/attempts/other/attempt-1/handoff/metadata.json");
        existing.put("payload", "publication-payloads/" + "a".repeat(64));
        existing.put("sha256", "sha256:" + "a".repeat(64)); existing.put("sizeBytes", 8L);
        setPayloadMap(source, List.of(existing));
        when(artifacts.listByWorkitem(10002L, 55411L)).thenReturn(List.of(row));

        DispatchCheckpointDO projected = new LegacyCheckpointNormalizer(artifacts, storage).normalize(source);

        assertNotSame(source, projected);
        JSONObject payloadMap = JSON.parseObject(
                new String(unpack(storage.get(projected.getOssRef())).get("publication-payload-map.json"), StandardCharsets.UTF_8));
        assertEquals(2, payloadMap.getJSONArray("entries").size());

        DispatchCheckpointDO duplicate = historicalHandoff();
        bindHandoff(duplicate, row, ACCEPTED);
        storage.delete(LegacyCheckpointNormalizer.compatibilityRef(duplicate.getOssRef()));
        JSONObject conflict = new JSONObject(true);
        conflict.put("target", "artifacts/accepted/handoff/metadata.json");
        conflict.put("payload", "publication-payloads/" + "a".repeat(64));
        conflict.put("sha256", "sha256:" + "a".repeat(64)); conflict.put("sizeBytes", 8L);
        setPayloadMap(duplicate, List.of(conflict));

        assertSame(duplicate, new LegacyCheckpointNormalizer(artifacts, storage).normalize(duplicate));
        assertFalse(storage.exists(LegacyCheckpointNormalizer.compatibilityRef(duplicate.getOssRef())));
    }

    @Test void repackagesArchiveThatAlreadyContainsHandoffPayload() throws Exception {
        byte[] payload = bytes("{\"nextOwner\":\"terraform-qa\",\"nextRole\":\"terraform-qa\"}");
        ArtifactDO row = handoffArtifact(13378L, payload);
        String payloadName = "publication-payloads/" + sha(payload);

        DispatchCheckpointDO source = historicalHandoff();
        bindHandoff(source, row, ACCEPTED);
        Map<String, byte[]> members = unpack(storage.get(source.getOssRef()));
        members.put(payloadName, payload);
        replaceSource(source, members);
        when(artifacts.listByWorkitem(10002L, 55411L)).thenReturn(List.of(row));

        DispatchCheckpointDO projected = new LegacyCheckpointNormalizer(artifacts, storage).normalize(source);

        assertNotSame(source, projected);
        assertArrayEquals(payload, unpack(storage.get(projected.getOssRef())).get(payloadName));

        DispatchCheckpointDO tampered = historicalHandoff();
        bindHandoff(tampered, row, ACCEPTED);
        storage.delete(LegacyCheckpointNormalizer.compatibilityRef(tampered.getOssRef()));
        Map<String, byte[]> bad = unpack(storage.get(tampered.getOssRef()));
        bad.put(payloadName, bytes("tampered"));
        replaceSource(tampered, bad);

        assertSame(tampered, new LegacyCheckpointNormalizer(artifacts, storage).normalize(tampered));
        assertFalse(storage.exists(LegacyCheckpointNormalizer.compatibilityRef(tampered.getOssRef())));
    }

    @Test void artifactByteLengthMismatchKeepsOriginal() throws Exception {
        byte[] payload = bytes("{\"nextOwner\":\"terraform-qa\",\"nextRole\":\"terraform-qa\"}");
        ArtifactDO row = handoffArtifact(13378L, payload);
        DispatchCheckpointDO source = historicalHandoff();
        bindHandoff(source, row, ACCEPTED);
        storage.put("bucket", row.getOssRef().substring("bucket/".length()), Arrays.copyOf(payload, payload.length + 1));
        when(artifacts.listByWorkitem(10002L, 55411L)).thenReturn(List.of(row));

        assertSame(source, new LegacyCheckpointNormalizer(artifacts, storage).normalize(source));
        assertFalse(storage.exists(LegacyCheckpointNormalizer.compatibilityRef(source.getOssRef())));
    }

    @Test void unavailableCorruptOrForeignHandoffNeverCreatesCompatibilityArchive() throws Exception {
        for (String failure : List.of("missing", "digest", "size", "oversized", "tenant", "workitem", "source", "dispatch", "ambiguous")) {
            ArtifactDO row = handoffArtifact(13378L, bytes("{\"nextOwner\":\"terraform-qa\",\"nextRole\":\"terraform-qa\"}"));
            DispatchCheckpointDO source = historicalHandoff();
            bindHandoff(source, row, ACCEPTED);
            switch (failure) {
                case "missing" -> storage.delete(row.getOssRef());
                case "digest" -> {
                    byte[] corrupt = storage.get(row.getOssRef()); corrupt[0] ^= 1;
                    storage.put("bucket", row.getOssRef().substring("bucket/".length()), corrupt);
                }
                case "size" -> row.setSize(0L);
                case "oversized" -> {
                    Map<String, byte[]> members = unpack(storage.get(source.getOssRef()));
                    JSONObject manifest = JSON.parseObject(new String(members.get(ACCEPTED), StandardCharsets.UTF_8));
                    manifest.getJSONArray("entries").getJSONObject(0).put("sizeBytes", 1024 * 1024 + 1);
                    members.put(ACCEPTED, bytes(manifest.toJSONString())); replaceSource(source, members);
                    row.setSize(1024L * 1024 + 1);
                }
                case "tenant" -> row.setTenantId(10003L);
                case "workitem" -> row.setWorkitemId(55412L);
                case "source" -> row.setSourceType("CHAT");
                case "dispatch" -> row.setDispatchId(13379L);
            }
            when(artifacts.listByWorkitem(10002L, 55411L)).thenReturn(failure.equals("ambiguous") ? List.of(row, row) : List.of(row));
            assertSame(source, new LegacyCheckpointNormalizer(artifacts, storage).normalize(source), failure);
            assertFalse(storage.exists(LegacyCheckpointNormalizer.compatibilityRef(source.getOssRef())), failure);
        }
    }

    DispatchCheckpointDO historicalHandoff() throws Exception {
        byte[] fixture;
        try (InputStream input = getClass().getResourceAsStream("/checkpoint/handoff-0.3.35.json")) { fixture = input.readAllBytes(); }
        JSONObject identity = JSON.parseObject(new String(fixture, StandardCharsets.UTF_8));
        byte[] archive = Base64.getDecoder().decode(identity.getString("archiveBase64"));
        assertEquals(identity.getString("sha256"), "sha256:" + sha(archive));
        assertEquals("d817ec4e85182dde301a036c9a79c25419da5757", identity.getString("producerGitHead"));
        Map<String, byte[]> members = unpack(archive);
        JSONObject metadata = JSON.parseObject(new String(members.get("checkpoint.json"), StandardCharsets.UTF_8));
        metadata.put("dispatchId", "13378"); metadata.put("checkpointSeq", 123L);
        members.put("checkpoint.json", bytes(metadata.toJSONString()));
        DispatchCheckpointDO source = source(PREFIX);
        replaceSource(source, members);
        return source;
    }

    void bindHandoff(DispatchCheckpointDO source, ArtifactDO row, String member) throws Exception {
        Map<String, byte[]> members = unpack(storage.get(source.getOssRef()));
        byte[] template = members.get(member);
        if (template == null) template = members.get(ACCEPTED);
        JSONObject manifest = JSON.parseObject(new String(template, StandardCharsets.UTF_8));
        JSONObject entry = manifest.getJSONArray("entries").getJSONObject(0);
        entry.put("remoteRef", row.getOssRef()); entry.put("sizeBytes", row.getSize());
        entry.put("sha256", "sha256:" + sha(storage.get(row.getOssRef())));
        members.put(member, bytes(manifest.toJSONString()));
        replaceSource(source, members);
    }

    JSONObject manifestEntry(String path, String disposition, String ref, byte[] payload) throws Exception {
        JSONObject entry = new JSONObject(true);
        entry.put("path", path); entry.put("sha256", "sha256:" + sha(payload));
        entry.put("sizeBytes", (long) payload.length); entry.put("disposition", disposition);
        if (ref != null) entry.put("remoteRef", ref);
        return entry;
    }

    void setEntries(DispatchCheckpointDO source, String member, List<JSONObject> entries) throws Exception {
        Map<String, byte[]> members = unpack(storage.get(source.getOssRef()));
        byte[] template = members.get(member);
        if (template == null) template = members.get(ACCEPTED);
        JSONObject manifest = JSON.parseObject(new String(template, StandardCharsets.UTF_8));
        manifest.put("entries", entries);
        members.put(member, bytes(manifest.toJSONString()));
        replaceSource(source, members);
    }

    void setEntryField(DispatchCheckpointDO source, String member, String field, Object value) throws Exception {
        Map<String, byte[]> members = unpack(storage.get(source.getOssRef()));
        JSONObject manifest = JSON.parseObject(new String(members.get(member), StandardCharsets.UTF_8));
        JSONObject entry = manifest.getJSONArray("entries").getJSONObject(0);
        if (value == null) entry.remove(field); else entry.put(field, value);
        members.put(member, bytes(manifest.toJSONString()));
        replaceSource(source, members);
    }

    void setPayloadMap(DispatchCheckpointDO source, List<JSONObject> entries) throws Exception {
        Map<String, byte[]> members = unpack(storage.get(source.getOssRef()));
        JSONObject map = new JSONObject(true);
        map.put("schemaVersion", "autowonder.publicationPayloadMap.v1");
        map.put("entries", entries);
        members.put("publication-payloads/" + "a".repeat(64), bytes("existing"));
        members.put("publication-payload-map.json", bytes(map.toJSONString()));
        replaceSource(source, members);
    }

    ArtifactDO artifactRow(Long dispatch, String name, String ossRef, long size) {
        ArtifactDO row = new ArtifactDO(); row.setTenantId(10002L); row.setWorkitemId(55411L);
        row.setDispatchId(dispatch); row.setSourceType("WORKITEM"); row.setName(name);
        row.setSize(size); row.setOssRef(ossRef);
        return row;
    }

    void replaceSource(DispatchCheckpointDO source, Map<String, byte[]> members) throws Exception {
        byte[] archive = pack(members);
        storage.put("bucket", PREFIX + "checkpoint.tar.gz", archive);
        source.setSha256(sha(archive)); source.setSizeBytes((long) archive.length);
    }

    ArtifactDO handoffArtifact(long dispatch, byte[] bytes) throws Exception {
        ArtifactDO row = new ArtifactDO(); row.setTenantId(10002L); row.setWorkitemId(55411L);
        row.setDispatchId(dispatch); row.setSourceType("WORKITEM"); row.setName("artifacts/output/handoff/metadata.json"); row.setSize((long) bytes.length);
        row.setOssRef(objectRef(dispatch, row.getName(), bytes));
        return row;
    }

    ArtifactDO legacyHandoffArtifact(long dispatch, byte[] bytes) {
        ArtifactDO row = new ArtifactDO(); row.setTenantId(10002L); row.setWorkitemId(55411L);
        row.setDispatchId(dispatch); row.setSourceType("WORKITEM"); row.setName("artifacts/output/handoff/metadata.json"); row.setSize((long) bytes.length);
        row.setOssRef(storage.put("bucket", PREFIX.replace("13378", Long.toString(dispatch)) + row.getName(), bytes).getOssRef());
        return row;
    }

    @Test void missingOrUnverifiableArtifactsKeepOriginalCandidate() throws Exception {
        DispatchCheckpointDO source = source(PREFIX);
        ArtifactDO row = artifact();
        when(artifacts.listByDispatch(10002L, 13378L)).thenReturn(List.of(row));
        LegacyCheckpointNormalizer normalizer = new LegacyCheckpointNormalizer(artifacts, storage);
        String key = row.getOssRef().substring("bucket/".length());
        storage.delete(row.getOssRef());
        assertSame(source, normalizer.normalize(source));
        storage.put("bucket", key, bytes("corrupt"));
        assertSame(source, normalizer.normalize(source));
        byte[] wrongDigest = content.clone(); wrongDigest[0] ^= 1;
        storage.put("bucket", key, wrongDigest);
        assertSame(source, normalizer.normalize(source));
        storage.put("bucket", key, content);
        row.setWorkitemId(55412L);
        assertSame(source, normalizer.normalize(source));
        row.setWorkitemId(55411L); row.setSourceType("CHAT");
        assertSame(source, normalizer.normalize(source));
        row.setSourceType("WORKITEM"); row.setOssRef("bucket/t/10002/workitem/55412/dispatch/13378/" + row.getName());
        assertSame(source, normalizer.normalize(source));
        row.setOssRef("bucket/t/10002/workitem/55411/dispatch/13378/objects/sha256/" + "0".repeat(64) + "/" + row.getName());
        assertSame(source, normalizer.normalize(source));
    }

    @Test void conflictingCompatibilityObjectIsNotOverwritten() throws Exception {
        DispatchCheckpointDO source = source(PREFIX);
        when(artifacts.listByDispatch(10002L, 13378L)).thenReturn(List.of(artifact()));
        String key = LegacyCheckpointNormalizer.compatibilityRef(source.getOssRef()).substring("bucket/".length());
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

    ArtifactDO artifact() throws Exception {
        ArtifactDO row = new ArtifactDO(); row.setTenantId(10002L); row.setWorkitemId(55411L);
        row.setDispatchId(13378L); row.setName("artifacts/output/" + PATH); row.setSize((long) content.length);
        row.setOssRef(objectRef(13378L, row.getName(), content)); return row;
    }

    ArtifactDO legacyArtifact() {
        ArtifactDO row = new ArtifactDO(); row.setTenantId(10002L); row.setWorkitemId(55411L);
        row.setDispatchId(13378L); row.setName("artifacts/output/" + PATH); row.setSize((long) content.length);
        row.setOssRef(storage.put("bucket", PREFIX + row.getName(), content).getOssRef()); return row;
    }

    String objectRef(long dispatch, String name, byte[] value) throws Exception {
        return storage.put("bucket", PREFIX.replace("13378", Long.toString(dispatch))
                + "objects/sha256/" + sha(value) + "/" + name, value).getOssRef();
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
