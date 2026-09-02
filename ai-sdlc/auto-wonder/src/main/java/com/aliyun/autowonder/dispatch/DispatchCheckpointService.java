package com.aliyun.autowonder.dispatch;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.storage.StoredObject;
import com.aliyun.autowonder.taskpackage.TaskArtifactRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;

@Service
public class DispatchCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(DispatchCheckpointService.class);

    private static final int DOWNLOAD_TTL_SECONDS = 600;
    private static final int RETAIN_CHECKPOINTS = 2;
    private static final int MAX_RESUME_LINEAGE_DEPTH = 16;
    private static final long MAX_CHECKPOINT_SCAN_BYTES = 256L * 1024 * 1024;
    private static final int MAX_CHECKPOINT_METADATA_BYTES = 1024 * 1024;
    private static final String CHECKPOINT_SCHEMA = "autowonder.runtimeCheckpoint.v1";
    private static final String REPO_STATE_SUFFIX = ".repo-state.json";
    private static final String SESSION_PINNED_EVENT = "agent.session_pinned";

    private final DispatchCheckpointDao checkpointDao;
    private final DispatchRuntimeEventDao runtimeEventDao;
    private final DispatchDao dispatchDao;
    private final ObjectStorage storage;
    private final String checkpointBucket;

    public DispatchCheckpointService(DispatchCheckpointDao checkpointDao,
            DispatchRuntimeEventDao runtimeEventDao, DispatchDao dispatchDao, ObjectStorage storage,
            OssProperties ossProperties) {
        this.checkpointDao = checkpointDao;
        this.runtimeEventDao = runtimeEventDao;
        this.dispatchDao = dispatchDao;
        this.storage = storage;
        this.checkpointBucket = ossProperties.resolveArtifactBucket();
    }

    public DispatchCheckpointDO store(DispatchDO dispatch, long checkpointSeq,
            String provider, String providerSessionId, String runtimeId,
            String activeStepId, byte[] archive) {
        DispatchCheckpointDO existing = checkpointDao.findByDispatchAndSeq(
                dispatch.getTenantId(), dispatch.getId(), checkpointSeq);
        if (existing != null) {
            ensureRepoRevisionSidecar(existing, archive);
            prune(dispatch);
            return existing;
        }
        String sha256 = sha256(archive);
        String key = "t/" + dispatch.getTenantId() + "/workitem/" + dispatch.getWorkitemId()
                + "/recovery/dispatch/" + dispatch.getId() + "/checkpoint-"
                + checkpointSeq + ".tar.gz";
        StoredObject stored = storage.put(checkpointBucket, key, archive);
        DispatchCheckpointDO checkpoint = new DispatchCheckpointDO();
        checkpoint.setTenantId(dispatch.getTenantId());
        checkpoint.setWorkitemId(dispatch.getWorkitemId());
        checkpoint.setDispatchId(dispatch.getId());
        checkpoint.setAgentId(dispatch.getAgentId());
        checkpoint.setCheckpointSeq(checkpointSeq);
        checkpoint.setProvider(trim(provider, 32));
        checkpoint.setProviderSessionId(trim(providerSessionId, 256));
        checkpoint.setRuntimeId(trim(runtimeId, 128));
        checkpoint.setExecutorId(dispatch.getExecutorId());
        checkpoint.setActiveStepId(trim(activeStepId, 128));
        checkpoint.setOssRef(stored.getOssRef());
        checkpoint.setSha256(sha256);
        checkpoint.setSizeBytes(stored.getSize());
        ensureRepoRevisionSidecar(checkpoint, archive);
        try {
            checkpointDao.insert(checkpoint);
            prune(dispatch);
            return checkpoint;
        } catch (DuplicateKeyException race) {
            DispatchCheckpointDO winner = checkpointDao.findByDispatchAndSeq(
                    dispatch.getTenantId(), dispatch.getId(), checkpointSeq);
            if (winner != null) {
                prune(dispatch);
                return winner;
            }
            throw race;
        }
    }

    private void prune(DispatchDO dispatch) {
        for (DispatchCheckpointDO obsolete : checkpointDao.listObsolete(
                dispatch.getTenantId(), dispatch.getId(), RETAIN_CHECKPOINTS)) {
            storage.delete(obsolete.getOssRef());
            storage.delete(repoStateRef(obsolete.getOssRef()));
            checkpointDao.deleteById(dispatch.getTenantId(), dispatch.getId(), obsolete.getId());
        }
    }

    /**
     * Return a materializable Git baseline from the newest valid durable checkpoint.
     * The checkpoint HEAD may exist only inside the uploaded bundle, so task packaging
     * must clone/checkout the recorded base before Runtime restores that bundle.
     */
    public TaskArtifactRef findRepoRevisionArtifact(long tenantId, long dispatchId) {
        List<DispatchCheckpointDO> checkpoints = checkpointDao.listLatestByDispatch(
                tenantId, dispatchId, RETAIN_CHECKPOINTS);
        if (checkpoints == null || checkpoints.isEmpty()) {
            DispatchCheckpointDO latest = checkpointDao.findLatestByDispatch(tenantId, dispatchId);
            checkpoints = latest == null ? List.of() : List.of(latest);
        }
        for (DispatchCheckpointDO checkpoint : checkpoints) {
            if (checkpoint == null || checkpoint.getOssRef() == null) {
                continue;
            }
            try {
                String sidecarRef = repoStateRef(checkpoint.getOssRef());
                byte[] sidecar = storage.exists(sidecarRef) ? storage.get(sidecarRef) : null;
                byte[] normalized = normalizeRevisionDocument(sidecar, "repositories");
                if (normalized == null) {
                    byte[] archive = storage.get(checkpoint.getOssRef());
                    if (archive == null || checkpoint.getSha256() == null
                            || !checkpoint.getSha256().equalsIgnoreCase(sha256(archive))) {
                        continue;
                    }
                    normalized = normalizeCheckpointRevision(archive);
                    if (normalized == null) {
                        continue;
                    }
                    sidecarRef = putRepoStateSidecar(checkpoint.getOssRef(), normalized);
                } else if (!Arrays.equals(sidecar, normalized)) {
                    // Repair legacy sidecars that exposed the checkpoint's
                    // local-only HEAD as a package checkout ref.
                    sidecarRef = putRepoStateSidecar(checkpoint.getOssRef(), normalized);
                }
                TaskArtifactRef ref = new TaskArtifactRef();
                ref.setName("checkpoint/" + checkpoint.getCheckpointSeq()
                        + "/deliverables/runtime-source-revision.json");
                ref.setOssRef(sidecarRef);
                return ref;
            } catch (RuntimeException checkpointError) {
                log.warn("checkpoint repo revision ignored dispatchId={} checkpointSeq={}",
                        dispatchId, checkpoint.getCheckpointSeq(), checkpointError);
            }
        }
        return null;
    }

    private void ensureRepoRevisionSidecar(DispatchCheckpointDO checkpoint, byte[] archive) {
        try {
            byte[] normalized = normalizeCheckpointRevision(archive);
            if (normalized != null) {
                putRepoStateSidecar(checkpoint.getOssRef(), normalized);
            }
        } catch (RuntimeException metadataError) {
            // The archive remains usable for recovery even if optional lineage extraction fails.
            log.warn("checkpoint repo metadata extraction failed dispatchId={} checkpointSeq={}",
                    checkpoint.getDispatchId(), checkpoint.getCheckpointSeq(), metadataError);
        }
    }

    private String putRepoStateSidecar(String checkpointRef, byte[] normalized) {
        int separator = checkpointRef == null ? -1 : checkpointRef.indexOf('/');
        if (separator <= 0 || separator == checkpointRef.length() - 1) {
            throw new IllegalArgumentException("invalid checkpoint ossRef");
        }
        StoredObject stored = storage.put(checkpointRef.substring(0, separator),
                checkpointRef.substring(separator + 1) + REPO_STATE_SUFFIX, normalized);
        return stored.getOssRef();
    }

    private String repoStateRef(String checkpointRef) {
        return checkpointRef == null ? null : checkpointRef + REPO_STATE_SUFFIX;
    }

    private byte[] normalizeCheckpointRevision(byte[] archive) {
        return normalizeRevisionDocument(extractCheckpointJson(archive), "repos");
    }

    private byte[] normalizeRevisionDocument(byte[] raw, String repositoriesField) {
        if (raw == null || raw.length == 0 || raw.length > MAX_CHECKPOINT_METADATA_BYTES) {
            return null;
        }
        JSONObject source = JSON.parseObject(new String(raw, StandardCharsets.UTF_8));
        if ("repos".equals(repositoriesField)
                && !CHECKPOINT_SCHEMA.equals(source.getString("schemaVersion"))) {
            return null;
        }
        var repositories = source.getJSONArray(repositoriesField);
        if (repositories == null || repositories.isEmpty() || repositories.size() > 100) {
            return null;
        }
        var normalizedRepositories = new com.alibaba.fastjson.JSONArray();
        for (int i = 0; i < repositories.size(); i++) {
            JSONObject repo = repositories.getJSONObject(i);
            if (repo == null) {
                continue;
            }
            String name = trim(repo.getString("name"), 255);
            String checkoutCommit = repo.getString("baseCommit");
            if (!validCommit(checkoutCommit)) {
                checkoutCommit = repo.getString("headCommit");
            }
            if (name == null || name.isBlank() || !validCommit(checkoutCommit)) {
                continue;
            }
            JSONObject normalized = new JSONObject(true);
            normalized.put("name", name);
            normalized.put("headCommit", checkoutCommit);
            if (validBranch(repo.getString("branch"))) {
                normalized.put("branch", repo.getString("branch"));
            }
            normalizedRepositories.add(normalized);
        }
        if (normalizedRepositories.isEmpty()) {
            return null;
        }
        JSONObject document = new JSONObject(true);
        document.put("schemaVersion", "autowonder.checkpointSourceRevision.v1");
        document.put("repositories", normalizedRepositories);
        return document.toJSONString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] extractCheckpointJson(byte[] archive) {
        if (archive == null || archive.length == 0) {
            return null;
        }
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(archive))) {
            long scanned = 0;
            while (scanned <= MAX_CHECKPOINT_SCAN_BYTES) {
                byte[] header = input.readNBytes(512);
                if (header.length == 0 || allZero(header)) {
                    return null;
                }
                if (header.length != 512) {
                    return null;
                }
                long size = tarSize(header);
                if (size < 0 || size > MAX_CHECKPOINT_SCAN_BYTES) {
                    return null;
                }
                String name = tarName(header);
                scanned += 512 + size;
                if (scanned > MAX_CHECKPOINT_SCAN_BYTES) {
                    return null;
                }
                if ("checkpoint.json".equals(name)) {
                    if (size > MAX_CHECKPOINT_METADATA_BYTES) {
                        return null;
                    }
                    byte[] content = input.readNBytes((int) size);
                    return content.length == size ? content : null;
                }
                input.skipNBytes(size);
                long padding = (512 - size % 512) % 512;
                input.skipNBytes(padding);
                scanned += padding;
            }
            return null;
        } catch (Exception invalidArchive) {
            return null;
        }
    }

    private String tarName(byte[] header) {
        int length = 0;
        while (length < 100 && header[length] != 0) {
            length++;
        }
        return new String(header, 0, length, StandardCharsets.UTF_8);
    }

    private long tarSize(byte[] header) {
        String raw = new String(header, 124, 12, StandardCharsets.US_ASCII)
                .replace("\0", "").trim();
        if (raw.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(raw, 8);
        } catch (NumberFormatException invalidSize) {
            return -1;
        }
    }

    private boolean allZero(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private boolean validCommit(String commit) {
        return commit != null && commit.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}");
    }

    private boolean validBranch(String branch) {
        if (branch == null || branch.isBlank() || branch.length() > 255
                || branch.startsWith("-") || branch.startsWith("/")
                || branch.endsWith("/") || branch.endsWith(".") || branch.endsWith(".lock")
                || branch.contains("..") || branch.contains("@{") || branch.contains("//")
                || "@".equals(branch)) {
            return false;
        }
        for (int i = 0; i < branch.length(); i++) {
            char c = branch.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)
                    || "~^:?*[\\".indexOf(c) >= 0) {
                return false;
            }
        }
        for (String part : branch.split("/")) {
            if (part.isEmpty() || part.startsWith(".") || part.endsWith(".lock")) {
                return false;
            }
        }
        return true;
    }

    public DispatchCheckpointDO latest(long tenantId, long dispatchId) {
        return checkpointDao.findLatestByDispatch(tenantId, dispatchId);
    }

    public boolean matchesDurableReceipt(long tenantId, long dispatchId,
            long checkpointSeq, String checkpointSha256) {
        if (checkpointSeq <= 0 || checkpointSha256 == null || checkpointSha256.isBlank()) {
            return false;
        }
        DispatchCheckpointDO stored = checkpointDao.findByDispatchAndSeq(tenantId, dispatchId, checkpointSeq);
        if (stored == null || stored.getCheckpointSeq() == null
                || stored.getCheckpointSeq() != checkpointSeq || stored.getSha256() == null) {
            return false;
        }
        String normalized = checkpointSha256.trim();
        if (normalized.regionMatches(true, 0, "sha256:", 0, 7)) {
            normalized = normalized.substring(7);
        }
        return stored.getSha256().equalsIgnoreCase(normalized);
    }

    public boolean hasResumableSession(long tenantId, long dispatchId) {
        return resolveResumeSource(tenantId, dispatchId, true).providerSession() != null;
    }

    public ResumeDescriptor descriptor(DispatchDO dispatch) {
        if (dispatch == null) {
            return null;
        }
        if (dispatch.getResumeFromDispatchId() == null) {
            if (("SIDE_INTERACTION".equals(dispatch.getResumeMode())
                    || "CANONICAL_INTERACTION".equals(dispatch.getResumeMode()))
                    || "COMMENT_REWORK".equals(dispatch.getResumeMode())) {
                return descriptor(dispatch, null,
                        null, null, null, null, null, List.of());
            }
            return null;
        }
        ResumeSource source = resolveResumeSource(dispatch.getTenantId(),
                dispatch.getResumeFromDispatchId(), false);
        List<DispatchCheckpointDO> checkpoints = source.checkpoints();
        DispatchCheckpointDO checkpoint = checkpoints.isEmpty() ? null : checkpoints.get(0);
        ProviderSession providerSession = source.providerSession();
        // A continuous Run may continue on a different executor after its
        // affinity deadline. Checkpoints are portable, provider-native session
        // ids are not; never leak one to the replacement executor.
        if ("DEGRADED_CONTINUOUS".equals(dispatch.getResumeMode())) {
            providerSession = null;
        }
        if (checkpoint == null) {
            return descriptor(dispatch, source.dispatchId(),
                    providerSession == null ? null : providerSession.provider(),
                    providerSession == null ? null : providerSession.sessionId(),
                    null, null, null, List.of());
        }
        List<ResumeCheckpointCandidate> candidates = ("RECOVERY".equals(dispatch.getResumeMode())
                || "CONTINUOUS".equals(dispatch.getResumeMode())
                || "DEGRADED_CONTINUOUS".equals(dispatch.getResumeMode())
                || "COMMENT_INTERACTION".equals(dispatch.getResumeMode())
                || "SIDE_INTERACTION".equals(dispatch.getResumeMode())
                || "CANONICAL_INTERACTION".equals(dispatch.getResumeMode())
                || "COMMENT_REWORK".equals(dispatch.getResumeMode()))
                ? checkpoints.stream().map(item -> new ResumeCheckpointCandidate(
                        storage.presignGet(item.getOssRef(), DOWNLOAD_TTL_SECONDS),
                        "sha256:" + item.getSha256(), item.getCheckpointSeq())).toList()
                : List.of();
        String downloadUrl = candidates.isEmpty() ? null : candidates.get(0).getDownloadUrl();
        log.info("dispatch resume checkpoint urls dispatchId={} sourceDispatchId={} checkpointSeq={}"
                        + " candidateCount={} checkpointDownloadUrl={}",
                dispatch.getId(), source.dispatchId(), checkpoint.getCheckpointSeq(),
                candidates.size(), downloadUrl);
        return descriptor(dispatch,
                source.dispatchId(),
                providerSession == null ? checkpoint.getProvider() : providerSession.provider(),
                "DEGRADED_CONTINUOUS".equals(dispatch.getResumeMode()) ? null
                        : (providerSession == null ? checkpoint.getProviderSessionId() : providerSession.sessionId()),
                downloadUrl,
                "sha256:" + checkpoint.getSha256(),
                checkpoint.getCheckpointSeq(),
                candidates);
    }

    /**
     * CANONICAL_INTERACTION is an internal scheduling mode. On the wire it is
     * intentionally represented as SIDE_INTERACTION so older runtimes still
     * execute an interaction instead of accidentally entering the formal SDLC.
     */
    private ResumeDescriptor descriptor(DispatchDO dispatch, Long sourceDispatchId,
            String provider, String providerSessionId, String checkpointDownloadUrl,
            String checkpointSha256, Long checkpointSeq,
            List<ResumeCheckpointCandidate> checkpointCandidates) {
        boolean canonical = "CANONICAL_INTERACTION".equals(dispatch.getResumeMode());
        return new ResumeDescriptor(
                canonical ? "SIDE_INTERACTION" : dispatch.getResumeMode(),
                canonical ? "CANONICAL" : ("SIDE_INTERACTION".equals(dispatch.getResumeMode()) ? "FORK" : null),
                sourceDispatchId, provider, providerSessionId,
                checkpointDownloadUrl, checkpointSha256, checkpointSeq, checkpointCandidates);
    }

    private ResumeSource resolveResumeSource(long tenantId, long initialDispatchId,
            boolean requireProviderSession) {
        long currentDispatchId = initialDispatchId;
        Set<Long> visited = new LinkedHashSet<>();
        ResumeSource fallback = null;
        while (visited.size() < MAX_RESUME_LINEAGE_DEPTH && visited.add(currentDispatchId)) {
            List<DispatchCheckpointDO> recordedCheckpoints = checkpointDao.listLatestByDispatch(
                    tenantId, currentDispatchId, RETAIN_CHECKPOINTS);
            if (recordedCheckpoints == null || recordedCheckpoints.isEmpty()) {
                DispatchCheckpointDO checkpoint = latest(tenantId, currentDispatchId);
                recordedCheckpoints = checkpoint == null ? List.of() : List.of(checkpoint);
            }
            DispatchCheckpointDO recordedCheckpoint = recordedCheckpoints.isEmpty()
                    ? null : recordedCheckpoints.get(0);
            List<DispatchCheckpointDO> checkpoints = recordedCheckpoints.stream()
                    .filter(this::checkpointAvailable)
                    .toList();
            ProviderSession providerSession = providerSession(tenantId, currentDispatchId, recordedCheckpoint);
            ResumeSource candidate = new ResumeSource(currentDispatchId, checkpoints, providerSession);
            if (fallback == null) {
                fallback = candidate;
            }
            if (providerSession != null || (!requireProviderSession && !checkpoints.isEmpty())) {
                return candidate;
            }
            DispatchDO current = dispatchDao.findById(currentDispatchId);
            if (current == null || !Objects.equals(current.getTenantId(), tenantId)
                    || current.getResumeFromDispatchId() == null) {
                break;
            }
            currentDispatchId = current.getResumeFromDispatchId();
        }
        return fallback != null ? fallback
                : new ResumeSource(initialDispatchId, List.of(), null);
    }

    private boolean checkpointAvailable(DispatchCheckpointDO checkpoint) {
        try {
            boolean available = checkpoint.getOssRef() != null && storage.exists(checkpoint.getOssRef());
            if (!available) {
                log.warn("checkpoint object is unavailable dispatchId={} checkpointSeq={} ossRef={}",
                        checkpoint.getDispatchId(), checkpoint.getCheckpointSeq(), checkpoint.getOssRef());
            }
            return available;
        } catch (RuntimeException storageCheckFailed) {
            log.warn("checkpoint availability check failed; preserving candidate dispatchId={} checkpointSeq={}",
                    checkpoint.getDispatchId(), checkpoint.getCheckpointSeq(), storageCheckFailed);
            return true;
        }
    }

    private ProviderSession providerSession(long tenantId, long dispatchId,
            DispatchCheckpointDO checkpoint) {
        if (checkpoint != null && checkpoint.getProviderSessionId() != null
                && !checkpoint.getProviderSessionId().isBlank()) {
            return new ProviderSession(checkpoint.getProvider(), checkpoint.getProviderSessionId());
        }
        DispatchRuntimeEventDO event = runtimeEventDao.findLatestByDispatchAndType(
                tenantId, dispatchId, SESSION_PINNED_EVENT);
        if (event == null || event.getDetailJson() == null) {
            return null;
        }
        try {
            JSONObject detail = JSON.parseObject(event.getDetailJson());
            String sessionId = detail.getString("sessionId");
            if (sessionId == null || sessionId.isBlank()) {
                return null;
            }
            return new ProviderSession(trim(detail.getString("provider"), 32),
                    trim(sessionId, 256));
        } catch (RuntimeException malformedEvent) {
            return null;
        }
    }

    private record ProviderSession(String provider, String sessionId) {
    }

    private record ResumeSource(long dispatchId, List<DispatchCheckpointDO> checkpoints,
            ProviderSession providerSession) {
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String normalized = new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
