package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.storage.StoredObject;
import com.aliyun.autowonder.taskpackage.TaskArtifactRef;
import com.aliyun.autowonder.util.SignedUrlRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
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

    @org.springframework.beans.factory.annotation.Autowired
    private LegacyCheckpointNormalizer legacyCheckpointNormalizer;

    public DispatchCheckpointService(DispatchCheckpointDao checkpointDao,
            DispatchRuntimeEventDao runtimeEventDao, DispatchDao dispatchDao, ObjectStorage storage,
            OssProperties ossProperties) {
        this.checkpointDao = checkpointDao;
        this.runtimeEventDao = runtimeEventDao;
        this.dispatchDao = dispatchDao;
        this.storage = storage;
        this.checkpointBucket = ossProperties.resolveArtifactBucket();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private DispatchResultStore resultStore;

    public DispatchCheckpointDO store(DispatchDO dispatch, long checkpointSeq,
            String provider, String providerSessionId, String runtimeId,
            String activeStepId, byte[] archive) {
        return store(dispatch, checkpointSeq, provider, providerSessionId, runtimeId, activeStepId, archive, null);
    }

    public DispatchCheckpointDO store(DispatchDO dispatch, long checkpointSeq,
            String provider, String providerSessionId, String runtimeId,
            String activeStepId, byte[] archive, String resultId) {
        if (resultStore == null) {
            if (resultId != null) throw new IllegalStateException("RESULT_SCHEMA_NOT_READY");
            return storeLocked(dispatch, checkpointSeq, provider, providerSessionId, runtimeId, activeStepId, archive, null);
        }
        resultStore.requireReady();
        return resultStore.transaction(() -> {
            resultStore.lockSubject(dispatch);
            resultStore.lockDispatch(dispatch.getTenantId(), dispatch.getId());
            return storeLocked(dispatch, checkpointSeq, provider, providerSessionId, runtimeId, activeStepId, archive, resultId);
        });
    }

    private DispatchCheckpointDO storeLocked(DispatchDO dispatch, long checkpointSeq,
            String provider, String providerSessionId, String runtimeId,
            String activeStepId, byte[] archive, String resultId) {
        DispatchCheckpointDO existing = checkpointDao.findByDispatchAndSeq(
                dispatch.getTenantId(), dispatch.getId(), checkpointSeq);
        if (existing != null) {
            if (!sha256(archive).equalsIgnoreCase(existing.getSha256()))
                throw new CheckpointConflictException();
            if (resultId != null) {
                if (existing.getCompletionResultId() != null && !resultId.equals(existing.getCompletionResultId()))
                    throw new CheckpointConflictException();
                try {
                    if (checkpointDao.pinCompletion(dispatch.getTenantId(), dispatch.getId(), checkpointSeq, resultId) != 1)
                        throw new CheckpointConflictException();
                } catch (DuplicateKeyException conflict) {
                    throw new CheckpointConflictException();
                }
                existing.setCompletionResultId(resultId);
            }
            ensureRepoRevisionSidecar(existing, archive);
            pruneBestEffort(dispatch);
            return existing;
        }
        if (DispatchStatus.PAUSED.equals(dispatch.getStatus())
                && checkpointDao.findPauseCheckpoint(dispatch.getTenantId(), dispatch.getId(), dispatch.getExecutorId()) != null) {
            throw new CheckpointConflictException(); // The confirmed pause boundary is immutable.
        }
        String sha256 = sha256(archive);
        String key = "t/" + dispatch.getTenantId() + "/workitem/" + dispatch.getWorkitemId()
                + "/recovery/dispatch/" + dispatch.getId() + "/checkpoint-"
                + checkpointSeq + "-" + sha256 + ".tar.gz";
        StoredObject stored = storage.put(checkpointBucket, key, archive);
        DispatchCheckpointDO checkpoint = new DispatchCheckpointDO();
        checkpoint.setTenantId(dispatch.getTenantId());
        checkpoint.setWorkitemId(dispatch.getWorkitemId());
        checkpoint.setDispatchId(dispatch.getId());
        checkpoint.setAgentId(dispatch.getAgentId());
        checkpoint.setCheckpointSeq(checkpointSeq);
        checkpoint.setCompletionResultId(resultId);
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
            pruneBestEffort(dispatch);
            return checkpoint;
        } catch (DuplicateKeyException race) {
            if (resultId != null) throw new CheckpointConflictException();
            DispatchCheckpointDO winner = checkpointDao.findByDispatchAndSeq(
                    dispatch.getTenantId(), dispatch.getId(), checkpointSeq);
            if (winner != null) {
                pruneBestEffort(dispatch);
                return winner;
            }
            throw race;
        }
    }

    /** Called under the pause/control transaction before acknowledging the exact boundary. */
    @org.springframework.transaction.annotation.Transactional
    public boolean confirmPauseReceipt(long tenantId, long executorId, long dispatchId, long seq, String hash) {
        String identity = "pause:" + tenantId + ":" + executorId + ":" + dispatchId + ":" + seq + ":" + hash;
        String resultId = java.util.UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        String payloadHash = sha256(identity.getBytes(StandardCharsets.UTF_8));
        if (resultStore == null) throw new IllegalStateException("RESULT_SCHEMA_NOT_READY");
        resultStore.requireReady();
        resultStore.lockDispatch(tenantId, dispatchId);
        DispatchResultStore.Decision previous = resultStore.find(tenantId, dispatchId, resultId);
        if (previous != null) return previous.executorId() == executorId && payloadHash.equals(previous.hash())
                && "PAUSE_CONFIRMED".equals(previous.ack().getString("reasonCode"));
        DispatchCheckpointDO pin = checkpointDao.findPauseCheckpoint(tenantId, dispatchId, executorId);
        if (pin != null && (!Objects.equals(pin.getCheckpointSeq(), seq)
                || !Objects.equals(pin.getExecutorId(), executorId))) return false;
        if (!matchesDurableReceipt(tenantId, dispatchId, seq, hash)) return false;
        DispatchCheckpointDO receipt = checkpointDao.findByDispatchAndSeq(tenantId, dispatchId, seq);
        if (receipt == null || !Objects.equals(receipt.getExecutorId(), executorId)) return false;
        JSONObject input = new JSONObject();
        input.put("dispatchId", dispatchId);input.put("resultId", resultId);
        input.put("checkpointSeq", seq);input.put("checkpointSha256", hash);
        JSONObject ack = new JSONObject();
        ack.put("disposition", "ACCEPTED");ack.put("reasonCode", "PAUSE_CONFIRMED");
        ack.put("accepted", true);ack.put("retryable", false);
        resultStore.insert(tenantId, executorId, input, payloadHash, ack, null);
        return true;
    }

    private void pruneBestEffort(DispatchDO dispatch) {
        try {
            prune(dispatch);
        } catch (RuntimeException cleanupFailure) {
            // The durable receipt is independent of retention. A later store retries cleanup.
            log.warn("checkpoint retention cleanup failed tenantId={} dispatchId={}",
                    dispatch.getTenantId(), dispatch.getId(), cleanupFailure);
        }
    }

    private void prune(DispatchDO dispatch) {
        if (hasLiveReferences(dispatch)) return;
        boolean paused = isPauseBoundary(dispatch);
        boolean replacement = paused && hasDurablePauseReplacement(dispatch);
        List<DispatchCheckpointDO> latest = paused && !replacement
                ? checkpointDao.listLatestByDispatch(dispatch.getTenantId(), dispatch.getId(), RETAIN_CHECKPOINTS) : List.of();
        DispatchCheckpointDO pausePin = paused
                ? checkpointDao.findPauseCheckpoint(dispatch.getTenantId(), dispatch.getId(), dispatch.getExecutorId()) : null;
        if (paused && !replacement && pausePin == null) return;
        checkpointDao.releaseResolvedPins(dispatch.getTenantId(), dispatch.getId());
        for (DispatchCheckpointDO obsolete : checkpointDao.listObsolete(
                dispatch.getTenantId(), dispatch.getId(), RETAIN_CHECKPOINTS)) {
            if (paused && !replacement && ((pausePin != null && Objects.equals(pausePin.getId(), obsolete.getId()))
                    || latest.stream().anyMatch(row -> Objects.equals(row.getId(), obsolete.getId())))) continue;
            // A committed tombstone removes the checkpoint from all materializable receipt queries.
            // Object deletion belongs to a later transaction; rollback never damages a live object.
            checkpointDao.markGarbage(dispatch.getTenantId(), dispatch.getId(), obsolete.getId());
        }
    }

    /** Follow unresolved lineage under the subject lock; a durable intermediate boundary cuts the dependency. */
    private boolean hasLiveReferences(DispatchDO source) {
        List<DispatchDO> rows = dispatchDao.listBySource(source.getTenantId(),
                source.executionSourceType().name(), source.getWorkitemId());
        if (rows == null) return true;
        var byId = new java.util.HashMap<Long, DispatchDO>();
        for (DispatchDO row : rows) if (Objects.equals(row.getTenantId(), source.getTenantId())
                && Objects.equals(row.getWorkitemId(), source.getWorkitemId())
                && row.executionSourceType() == source.executionSourceType()) byId.put(row.getId(), row);
        for (DispatchDO consumer : byId.values()) {
            if (Objects.equals(consumer.getId(), source.getId()) || DispatchStatus.isTerminal(consumer.getStatus())
                    || DispatchStatus.PAUSED.equals(consumer.getStatus())) continue;
            var queue = new java.util.ArrayDeque<Long>();
            if (consumer.getResumeFromDispatchId() != null) queue.add(consumer.getResumeFromDispatchId());
            if (consumer.getDeliverySourceDispatchId() != null) queue.add(consumer.getDeliverySourceDispatchId());
            // Legacy work may encode its source only in an idempotency key. Do not infer absence of a reference.
            if (queue.isEmpty() && consumer.getId() > source.getId()) return true;
            Set<Long> visited = new LinkedHashSet<>();
            while (!queue.isEmpty()) {
                Long id = queue.removeFirst();
                if (Objects.equals(id, source.getId())) return true;
                if (!visited.add(id)) continue;
                if (visited.size() > MAX_RESUME_LINEAGE_DEPTH) return true;
                DispatchDO parent = byId.get(id);
                if (parent == null) return true;
                DispatchCheckpointDO boundary = DispatchStatus.PAUSED.equals(parent.getStatus())
                        ? checkpointDao.findPauseCheckpoint(source.getTenantId(), id, parent.getExecutorId())
                        : checkpointDao.findLatestByDispatch(source.getTenantId(), id);
                if (boundary != null && Objects.equals(boundary.getExecutorId(), parent.getExecutorId())) continue;
                if (parent.getResumeFromDispatchId() != null) queue.add(parent.getResumeFromDispatchId());
                if (parent.getDeliverySourceDispatchId() != null) queue.add(parent.getDeliverySourceDispatchId());
            }
        }
        return false;
    }

    private boolean isPauseBoundary(DispatchDO source) {
        return DispatchStatus.PAUSED.equals(source.getStatus())
                || (DispatchStatus.CANCELED.equals(source.getStatus()) && checkpointDao.hasPauseDecision(
                    source.getTenantId(), source.getId(), source.getExecutorId()) > 0);
    }

    private boolean hasDurablePauseReplacement(DispatchDO source) {
        List<DispatchDO> rows = dispatchDao.listBySource(source.getTenantId(),
                source.executionSourceType().name(), source.getWorkitemId());
        if (rows == null) return false;
        for (DispatchDO newer : rows) {
            if (newer.getId() <= source.getId() || !Objects.equals(newer.getAgentId(), source.getAgentId())
                    || !Objects.equals(newer.getTenantId(), source.getTenantId())
                    || !Objects.equals(newer.getWorkitemId(), source.getWorkitemId())
                    || newer.executionSourceType() != source.executionSourceType()) continue;
            DispatchCheckpointDO boundary = DispatchStatus.PAUSED.equals(newer.getStatus())
                    ? checkpointDao.findPauseCheckpoint(source.getTenantId(), newer.getId(), newer.getExecutorId())
                    : DispatchStatus.SUCCEEDED.equals(newer.getStatus())
                        ? checkpointDao.findLatestByDispatch(source.getTenantId(), newer.getId()) : null;
            if (boundary != null && Objects.equals(boundary.getExecutorId(), newer.getExecutorId())
                    && boundary.getOssRef() != null && storage.exists(boundary.getOssRef())) return true;
        }
        return false;
    }

    /** Invoked by the existing compensation sweep; no separate scheduler or in-memory retry queue. */
    public void reconcileRetention() {
        resultStore.requireReady();
        for (DispatchCheckpointDO subject : checkpointDao.listRetentionSubjects(RETAIN_CHECKPOINTS, 200)) {
            try {
                resultStore.transaction(() -> {
                    DispatchDO dispatch = dispatchDao.findById(subject.getDispatchId());
                    if (dispatch != null) {
                        resultStore.lockSubject(dispatch);
                        resultStore.lockDispatch(subject.getTenantId(), subject.getDispatchId());
                        prune(dispatch);
                    }
                    return null;
                });
            } catch (RuntimeException failure) {
                log.warn("checkpoint retention marking deferred dispatchId={}", subject.getDispatchId(), failure);
            }
        }
        for (DispatchCheckpointDO pending : checkpointDao.listGarbage(200)) {
            try {
                resultStore.transaction(() -> {
                    DispatchDO dispatch = dispatchDao.findById(pending.getDispatchId());
                    if (dispatch != null) resultStore.lockSubject(dispatch);
                    resultStore.lockDispatch(pending.getTenantId(), pending.getDispatchId());
                    if (dispatch != null && (hasLiveReferences(dispatch)
                            || (isPauseBoundary(dispatch) && !hasDurablePauseReplacement(dispatch)))) {
                        checkpointDao.restoreGarbage(pending.getTenantId(), pending.getDispatchId(), pending.getId());
                        return null;
                    }
                    if (checkpointDao.restoreReferencedGarbage(pending.getTenantId(), pending.getDispatchId(), pending.getId()) == 1)
                        return null;
                    DispatchCheckpointDO garbage = checkpointDao.findGarbageById(pending.getId());
                    if (garbage == null) return null;
                    storage.delete(garbage.getOssRef());
                    storage.delete(repoStateRef(garbage.getOssRef()));
                    storage.delete(LegacyCheckpointNormalizer.compatibilityRef(garbage.getOssRef()));
                    checkpointDao.deleteById(garbage.getTenantId(), garbage.getDispatchId(), garbage.getId());
                    return null;
                });
            } catch (RuntimeException failure) {
                // Retain the committed tombstone so partial object deletion is idempotently retried.
                log.warn("checkpoint object collection deferred checkpointId={}", pending.getId(), failure);
            }
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
        var normalizedRepositories = new com.aliyun.autowonder.json.JSONArray();
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

    public boolean matchesCompletionReceipt(long tenantId, long dispatchId, String resultId,
            long checkpointSeq, String hash) {
        DispatchCheckpointDO checkpoint = checkpointDao.findByDispatchAndSeq(tenantId, dispatchId, checkpointSeq);
        return checkpoint != null && resultId.equals(checkpoint.getCompletionResultId())
                && matchesDurableReceipt(tenantId, dispatchId, checkpointSeq, hash);
    }

    public static class CheckpointConflictException extends RuntimeException { }

    /** The dispatch is not a resumable execution or the checkpoint is not one of its restore candidates. */
    public static class CheckpointNotRestorableException extends RuntimeException { }

    /** No durable checkpoint row with that sequence exists in this dispatch's resume lineage. */
    public static class CheckpointNotFoundException extends RuntimeException { }

    /** The checkpoint row exists but the object behind it is gone from object storage. */
    public static class CheckpointObjectMissingException extends RuntimeException { }

    /** The requested checkpoint identity cannot be resolved. */
    public static class CheckpointInvalidRequestException extends RuntimeException { }

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
        List<ResumeCheckpointCandidate> candidates = resumableCheckpointMode(dispatch.getResumeMode())
                ? checkpoints.stream().map(item -> legacyCheckpointNormalizer == null
                        ? item : legacyCheckpointNormalizer.normalize(item))
                        .map(item -> new ResumeCheckpointCandidate(
                        storage.presignGet(item.getOssRef(), DOWNLOAD_TTL_SECONDS),
                        "sha256:" + item.getSha256(), item.getCheckpointSeq())).toList()
                : List.of();
        String downloadUrl = candidates.isEmpty() ? null : candidates.get(0).getDownloadUrl();
        log.info("dispatch resume checkpoint urls dispatchId={} sourceDispatchId={} checkpointSeq={}"
                        + " candidateCount={} checkpointDownloadUrl={}",
                dispatch.getId(), source.dispatchId(), checkpoint.getCheckpointSeq(),
                candidates.size(), SignedUrlRedactor.redact(downloadUrl));
        return descriptor(dispatch,
                source.dispatchId(),
                providerSession == null ? checkpoint.getProvider() : providerSession.provider(),
                "DEGRADED_CONTINUOUS".equals(dispatch.getResumeMode()) ? null
                        : (providerSession == null ? checkpoint.getProviderSessionId() : providerSession.sessionId()),
                downloadUrl,
                candidates.isEmpty() ? "sha256:" + checkpoint.getSha256() : candidates.get(0).getSha256(),
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

    /**
     * Re-sign the download link of one checkpoint that the given dispatch is
     * allowed to restore. Only a sequence number is accepted: callers can never
     * ask the platform to sign an arbitrary object key.
     */
    public CheckpointDownloadLink refreshDownloadLink(DispatchDO dispatch, long checkpointSeq) {
        if (dispatch == null || dispatch.getResumeFromDispatchId() == null
                || !resumableCheckpointMode(dispatch.getResumeMode())) {
            throw new CheckpointNotRestorableException();
        }
        if (checkpointSeq <= 0) {
            throw new CheckpointInvalidRequestException();
        }
        ResumeSource source = resolveResumeSource(dispatch.getTenantId(),
                dispatch.getResumeFromDispatchId(), false);
        DispatchCheckpointDO recorded = findCheckpoint(source.recordedCheckpoints(), checkpointSeq);
        if (recorded == null) {
            DispatchCheckpointDO lineageRow = findCheckpointInLineage(dispatch, checkpointSeq);
            if (lineageRow == null) {
                throw new CheckpointNotFoundException();
            }
            if (!checkpointAvailable(lineageRow)) {
                throw new CheckpointObjectMissingException();
            }
            throw new CheckpointNotRestorableException();
        }
        DispatchCheckpointDO available = findCheckpoint(source.checkpoints(), checkpointSeq);
        if (available == null) {
            throw new CheckpointObjectMissingException();
        }
        DispatchCheckpointDO checkpoint = legacyCheckpointNormalizer == null
                ? available : legacyCheckpointNormalizer.normalize(available);
        if (checkpoint.getOssRef() == null || checkpoint.getOssRef().isBlank()) {
            throw new CheckpointObjectMissingException();
        }
        String downloadUrl = storage.presignGet(checkpoint.getOssRef(), DOWNLOAD_TTL_SECONDS);
        log.info("checkpoint download url refreshed dispatchId={} sourceDispatchId={} checkpointSeq={}"
                        + " ttlSeconds={}",
                dispatch.getId(), source.dispatchId(), checkpointSeq, DOWNLOAD_TTL_SECONDS);
        return new CheckpointDownloadLink(checkpointSeq, downloadUrl,
                Instant.now().plusSeconds(DOWNLOAD_TTL_SECONDS).toString(),
                "sha256:" + checkpoint.getSha256(), DOWNLOAD_TTL_SECONDS);
    }

    private static boolean resumableCheckpointMode(String mode) {
        return "RECOVERY".equals(mode)
                || "CONTINUOUS".equals(mode)
                || "DEGRADED_CONTINUOUS".equals(mode)
                || "COMMENT_INTERACTION".equals(mode)
                || "SIDE_INTERACTION".equals(mode)
                || "CANONICAL_INTERACTION".equals(mode)
                || "COMMENT_REWORK".equals(mode);
    }

    private DispatchCheckpointDO findCheckpoint(List<DispatchCheckpointDO> checkpoints, long checkpointSeq) {
        if (checkpoints == null) {
            return null;
        }
        for (DispatchCheckpointDO checkpoint : checkpoints) {
            if (checkpoint != null && checkpoint.getCheckpointSeq() != null
                    && checkpoint.getCheckpointSeq() == checkpointSeq) {
                return checkpoint;
            }
        }
        return null;
    }

    /**
     * Distinguishes "pruned or not a restore candidate" from "never existed".
     * Everything reachable from this dispatch's resume lineage is owned by it;
     * any other dispatch's checkpoint stays indistinguishable from a missing one.
     */
    private DispatchCheckpointDO findCheckpointInLineage(DispatchDO dispatch, long checkpointSeq) {
        Long current = dispatch.getResumeFromDispatchId();
        Set<Long> visited = new LinkedHashSet<>();
        while (current != null && visited.size() < MAX_RESUME_LINEAGE_DEPTH && visited.add(current)) {
            DispatchCheckpointDO row = checkpointDao.findByDispatchAndSeq(
                    dispatch.getTenantId(), current, checkpointSeq);
            if (row != null) {
                return row;
            }
            DispatchDO source = dispatchDao.findById(current);
            if (source == null || !Objects.equals(source.getTenantId(), dispatch.getTenantId())) {
                return null;
            }
            current = source.getResumeFromDispatchId();
        }
        return null;
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
            ResumeSource candidate = new ResumeSource(currentDispatchId, recordedCheckpoints,
                    checkpoints, providerSession);
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
                : new ResumeSource(initialDispatchId, List.of(), List.of(), null);
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

    private record ResumeSource(long dispatchId, List<DispatchCheckpointDO> recordedCheckpoints,
            List<DispatchCheckpointDO> checkpoints, ProviderSession providerSession) {
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
