package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.storage.ObjectStorage;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Read-time projection for legacy receipts and omitted handoff control bytes. */
@Component
final class LegacyCheckpointNormalizer {
    private static final Logger log = LoggerFactory.getLogger(LegacyCheckpointNormalizer.class);
    private static final int MAX_ARCHIVE_BYTES = 256 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final int MAX_ARTIFACT_BYTES = 50 * 1024 * 1024;
    private static final String SUFFIX = ".compat-handoff-v2.tar.gz";
    private static final String HANDOFF = "handoff/metadata.json";
    private static final String PAYLOAD_MAP = "publication-payload-map.json";
    private final ArtifactDao artifacts;
    private final ObjectStorage storage;

    LegacyCheckpointNormalizer(ArtifactDao artifacts, ObjectStorage storage) {
        this.artifacts = artifacts;
        this.storage = storage;
    }

    static String compatibilityRef(String original) { return original + SUFFIX; }

    static String legacyCompatibilityRef(String original) { return original + ".compat-exact-ref-v1.tar.gz"; }

    DispatchCheckpointDO normalize(DispatchCheckpointDO source) {
        try {
            return project(source);
        } catch (Exception failure) {
            // Keep the original candidate and Runtime's existing validation/fallback behavior.
            log.warn("legacy checkpoint compatibility unavailable dispatchId={} checkpointSeq={} reason={}",
                    source.getDispatchId(), source.getCheckpointSeq(), failure.getClass().getSimpleName());
            return source;
        }
    }

    private DispatchCheckpointDO project(DispatchCheckpointDO source) throws Exception {
        require(source.getSizeBytes() != null && source.getSizeBytes() > 0
                && source.getSizeBytes() <= MAX_ARCHIVE_BYTES);
        byte[] original = storage.get(source.getOssRef());
        require(original != null && original.length == source.getSizeBytes()
                && sha(original).equalsIgnoreCase(source.getSha256()));
        Map<String, byte[]> members = scanManifests(original);
        JSONObject metadata = json(members.get("checkpoint.json"));
        if (!"autowonder.runtimeCheckpoint.v2".equals(metadata.getString("schemaVersion"))) return source;
        require(Objects.equals(source.getDispatchId().toString(), metadata.getString("dispatchId"))
                && Objects.equals(source.getCheckpointSeq(), metadata.getLong("checkpointSeq")));
        // A pending transaction has additional semantics; leave it to Runtime.
        if (members.containsKey("state/accepted-publication-pending.json")) return source;
        String prefix = "t/" + source.getTenantId() + "/workitem/" + source.getWorkitemId()
                + "/dispatch/" + source.getDispatchId() + "/";
        List<ArtifactDO> rows = null;
        Map<String, String> verified = new HashMap<>();
        boolean changed = false;
        for (Map.Entry<String, byte[]> member : members.entrySet()) {
            if (!manifest(member.getKey())) continue;
            JSONObject document = json(member.getValue());
            require("autowonder.publishManifest.v1".equals(document.getString("schemaVersion")));
            JSONArray entries = document.getJSONArray("entries");
            if (entries == null) continue;
            boolean edited = false;
            for (int i = 0; i < entries.size(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                if (!"reference_only".equals(entry.getString("disposition"))
                        || !prefix.equals(entry.getString("remoteRef"))) continue;
                if (rows == null) rows = artifacts.listByDispatch(source.getTenantId(), source.getDispatchId());
                String path = entry.getString("path"), digest = entry.getString("sha256");
                Long size = entry.getLong("sizeBytes");
                require(safePath(path) && size != null && size >= 0 && size <= MAX_ARTIFACT_BYTES
                        && digest != null && digest.matches("sha256:[0-9a-fA-F]{64}"));
                String cacheKey = path + ":" + digest + ":" + size;
                String ref = verified.get(cacheKey);
                if (ref == null) {
                    ref = resolve(source, rows, prefix, path, digest, size);
                    verified.put(cacheKey, ref);
                }
                entry.put("remoteRef", ref);
                edited = true;
            }
            if (edited) {
                member.setValue(document.toJSONString().getBytes(StandardCharsets.UTF_8));
                changed = true;
            }
        }
        changed |= restoreHandoffControl(source, members);
        if (!changed) return source;
        // Preserve metadata, progress, receipts and producer stateSha256 byte-for-byte.
        // stateSha256 is a producer fingerprint, not the transport archive checksum.
        byte[] compatible = pack(original, members);
        String ref = compatibilityRef(source.getOssRef());
        byte[] existing = storage.exists(ref) ? storage.get(ref) : null;
        if (existing != null) require(Arrays.equals(existing, compatible));
        else {
            int slash = ref.indexOf('/');
            require(slash > 0);
            storage.put(ref.substring(0, slash), ref.substring(slash + 1), compatible);
        }
        DispatchCheckpointDO projected = new DispatchCheckpointDO();
        BeanUtils.copyProperties(source, projected);
        projected.setOssRef(ref);
        projected.setSha256(sha(compatible));
        projected.setSizeBytes((long) compatible.length);
        return projected;
    }

    private boolean restoreHandoffControl(DispatchCheckpointDO source, Map<String, byte[]> members) throws Exception {
        JSONObject payloadMap = null;
        List<ArtifactDO> rows = null;
        Map<String, byte[]> payloads = new LinkedHashMap<>();
        long restoredBytes = 0;
        boolean changed = false;
        for (Map.Entry<String, byte[]> member : members.entrySet()) {
            if (!manifest(member.getKey())) continue;
            JSONObject document = json(member.getValue());
            JSONArray entries = document.getJSONArray("entries");
            if (entries == null) continue;
            boolean edited = false;
            for (int i = 0; i < entries.size(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                if (!HANDOFF.equals(entry.getString("path"))
                        || !"reference_only".equals(entry.getString("disposition"))) continue;
                if (rows == null) rows = artifacts.listByWorkitem(source.getTenantId(), source.getWorkitemId());
                String ref = entry.getString("remoteRef"), digest = entry.getString("sha256");
                Long size = entry.getLong("sizeBytes");
                require(size != null && size >= 0 && size <= MAX_JSON_BYTES
                        && digest != null && digest.matches("sha256:[0-9a-fA-F]{64}"));
                List<ArtifactDO> matches = rows == null ? List.of() : rows.stream()
                        .filter(row -> Objects.equals(ref, row.getOssRef())).toList();
                require(matches.size() == 1);
                ArtifactDO row = matches.get(0);
                String prefix = "t/" + source.getTenantId() + "/workitem/" + source.getWorkitemId() + "/dispatch/";
                int slash = ref == null ? -1 : ref.indexOf('/');
                String hash = digest.substring(7).toLowerCase(Locale.ROOT);
                require(Objects.equals(row.getTenantId(), source.getTenantId())
                        && Objects.equals(row.getWorkitemId(), source.getWorkitemId())
                        && "WORKITEM".equals(row.getSourceType()) && Objects.equals(row.getSize(), size)
                        && row.getDispatchId() != null && safePath(row.getName())
                        && slash > 0 && ref.substring(0, slash).matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")
                        && ownedObjectKey(ref.substring(slash + 1),
                                prefix + row.getDispatchId() + "/", row.getName(), hash));
                String payload = "publication-payloads/" + hash;
                byte[] bytes = payloads.get(payload);
                if (bytes == null) {
                    require(size <= MAX_ARCHIVE_BYTES - restoredBytes);
                    restoredBytes += size;
                    bytes = storage.get(ref);
                    require(bytes != null && bytes.length == size && sha(bytes).equals(hash));
                    payloads.put(payload, bytes);
                }
                require(bytes.length == size);
                if (payloadMap == null) {
                    payloadMap = json(members.get(PAYLOAD_MAP));
                    require("autowonder.publicationPayloadMap.v1".equals(payloadMap.getString("schemaVersion")));
                    if (payloadMap.getJSONArray("entries") == null) payloadMap.put("entries", new JSONArray());
                }
                String target = member.getKey().substring(0, member.getKey().lastIndexOf('/') + 1) + HANDOFF;
                for (Object mapping : payloadMap.getJSONArray("entries")) {
                    require(!target.equals(((JSONObject) mapping).getString("target")));
                }
                JSONObject mapping = new JSONObject(true);
                mapping.put("target", target); mapping.put("payload", payload);
                mapping.put("sha256", "sha256:" + hash); mapping.put("sizeBytes", size);
                payloadMap.getJSONArray("entries").add(mapping);
                entry.put("disposition", "upload"); entry.remove("remoteRef");
                edited = true;
            }
            if (edited) {
                member.setValue(document.toJSONString().getBytes(StandardCharsets.UTF_8));
                changed = true;
            }
        }
        if (changed) {
            members.put(PAYLOAD_MAP, payloadMap.toJSONString().getBytes(StandardCharsets.UTF_8));
            members.putAll(payloads);
        }
        return changed;
    }

    private String resolve(DispatchCheckpointDO source, List<ArtifactDO> rows, String prefix,
                           String path, String digest, long size) throws Exception {
        String name = "artifacts/output/" + path;
        List<ArtifactDO> matches = rows == null ? List.of() : rows.stream()
                .filter(row -> name.equals(row.getName())).toList();
        require(matches.size() == 1);
        ArtifactDO row = matches.get(0);
        require(Objects.equals(row.getTenantId(), source.getTenantId())
                && Objects.equals(row.getWorkitemId(), source.getWorkitemId())
                && Objects.equals(row.getDispatchId(), source.getDispatchId())
                && "WORKITEM".equals(row.getSourceType()) && Objects.equals(row.getSize(), size));
        String hash = digest.substring(7).toLowerCase(Locale.ROOT);
        String ref = row.getOssRef();
        int slash = ref == null ? -1 : ref.indexOf('/');
        require(slash > 0 && ref.substring(0, slash).matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")
                && ownedObjectKey(ref.substring(slash + 1), prefix, name, hash));
        byte[] bytes = storage.get(ref);
        require(bytes != null && bytes.length == size && sha(bytes).equals(hash));
        return ref;
    }

    // Current uploads are content-addressed under objects/sha256/<digest>/; legacy receipts used the flat dispatch key.
    private static boolean ownedObjectKey(String key, String dispatchPrefix, String name, String hash) {
        return key.equals(dispatchPrefix + name)
                || key.equals(dispatchPrefix + "objects/sha256/" + hash + "/" + name);
    }

    private static boolean manifest(String name) {
        return "artifacts/accepted/publish-manifest.json".equals(name)
                || name.matches("artifacts/attempts/[^/]+/attempt-[0-9]+/publish-manifest\\.json");
    }

    private static JSONObject json(byte[] bytes) {
        require(bytes != null && bytes.length <= MAX_JSON_BYTES);
        JSONObject document = JSON.parseObject(new String(bytes, StandardCharsets.UTF_8));
        require(document != null);
        return document;
    }

    private static boolean safePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\")
                || path.contains(":")) return false;
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }

    private static Map<String, byte[]> scanManifests(byte[] archive) throws IOException {
        Map<String, byte[]> members = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        long total = 0;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new GZIPInputStream(new ByteArrayInputStream(archive)))) {
            for (TarArchiveEntry entry; (entry = tar.getNextTarEntry()) != null;) {
                String name = entry.getName();
                String path = entry.isDirectory() && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                require(safePath(path) && entry.isCheckSumOK() && (entry.isFile() || entry.isDirectory())
                        && !entry.isLink() && !entry.isSymbolicLink() && !entry.isSparse()
                        && names.add(name) && names.size() <= 10000);
                total += entry.getSize();
                require(entry.getSize() >= 0 && total <= MAX_ARCHIVE_BYTES);
                // Normal modern checkpoints need only this bounded metadata scan.
                // Never retain unrelated artifact or repository payloads in memory.
                if (manifest(name) || name.equals("checkpoint.json") || name.equals(PAYLOAD_MAP)
                        || name.equals("state/accepted-publication-pending.json")) {
                    require(entry.getSize() <= MAX_JSON_BYTES);
                    byte[] data = tar.readNBytes((int) entry.getSize());
                    require(data.length == entry.getSize());
                    members.put(name, data);
                }
            }
        }
        return members;
    }

    private static byte[] pack(byte[] original, Map<String, byte[]> replacements) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, byte[]> remaining = new LinkedHashMap<>(replacements);
        long total = 0;
        int count = 0;
        try (TarArchiveInputStream input = new TarArchiveInputStream(new GZIPInputStream(new ByteArrayInputStream(original)));
             TarArchiveOutputStream output = new TarArchiveOutputStream(new GZIPOutputStream(out))) {
            output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (TarArchiveEntry entry; (entry = input.getNextTarEntry()) != null;) {
                byte[] replacement = remaining.remove(entry.getName());
                long originalSize = entry.getSize();
                if (replacement != null && entry.getName().startsWith("publication-payloads/")) {
                    require(originalSize == replacement.length && Arrays.equals(replacement, input.readNBytes(replacement.length)));
                }
                if (replacement != null) entry.setSize(replacement.length);
                total += entry.getSize();
                require(++count <= 10000 && total <= MAX_ARCHIVE_BYTES);
                output.putArchiveEntry(entry);
                if (replacement != null) output.write(replacement);
                else input.transferTo(output);
                output.closeArchiveEntry();
                // The input stream retains this header and uses its size to skip padding.
                entry.setSize(originalSize);
            }
            for (var addition : remaining.entrySet()) {
                require(addition.getKey().startsWith("publication-payloads/") && safePath(addition.getKey()));
                total += addition.getValue().length;
                require(++count <= 10000 && total <= MAX_ARCHIVE_BYTES);
                TarArchiveEntry entry = new TarArchiveEntry(addition.getKey());
                entry.setSize(addition.getValue().length); entry.setMode(0600); entry.setModTime(0);
                output.putArchiveEntry(entry); output.write(addition.getValue()); output.closeArchiveEntry();
            }
        }
        require(out.size() <= MAX_ARCHIVE_BYTES);
        return out.toByteArray();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("unverifiable legacy checkpoint");
    }
}
