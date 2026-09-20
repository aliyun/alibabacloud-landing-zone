package com.aliyun.autowonder.dispatch;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
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

/** Read-time projection for legacy checkpoints that omitted files using a directory receipt. */
@Component
final class LegacyCheckpointNormalizer {
    private static final Logger log = LoggerFactory.getLogger(LegacyCheckpointNormalizer.class);
    private static final int MAX_ARCHIVE_BYTES = 256 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final int MAX_ARTIFACT_BYTES = 50 * 1024 * 1024;
    private static final String SUFFIX = ".compat-exact-ref-v1.tar.gz";
    private final ArtifactDao artifacts;
    private final ObjectStorage storage;

    LegacyCheckpointNormalizer(ArtifactDao artifacts, ObjectStorage storage) {
        this.artifacts = artifacts;
        this.storage = storage;
    }

    static String compatibilityRef(String original) { return original + SUFFIX; }

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
        String ref = row.getOssRef();
        int slash = ref == null ? -1 : ref.indexOf('/');
        require(slash > 0 && ref.substring(0, slash).matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")
                && ref.substring(slash + 1).equals(prefix + name));
        byte[] bytes = storage.get(ref);
        require(bytes != null && bytes.length == size && sha(bytes).equalsIgnoreCase(digest.substring(7)));
        return ref;
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
                if (manifest(name) || name.equals("checkpoint.json")
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
        try (TarArchiveInputStream input = new TarArchiveInputStream(new GZIPInputStream(new ByteArrayInputStream(original)));
             TarArchiveOutputStream output = new TarArchiveOutputStream(new GZIPOutputStream(out))) {
            output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (TarArchiveEntry entry; (entry = input.getNextTarEntry()) != null;) {
                byte[] replacement = replacements.get(entry.getName());
                long originalSize = entry.getSize();
                if (replacement != null) entry.setSize(replacement.length);
                output.putArchiveEntry(entry);
                if (replacement != null) output.write(replacement);
                else input.transferTo(output);
                output.closeArchiveEntry();
                // The input stream retains this header and uses its size to skip padding.
                entry.setSize(originalSize);
            }
        }
        return out.toByteArray();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("unverifiable legacy checkpoint");
    }
}
