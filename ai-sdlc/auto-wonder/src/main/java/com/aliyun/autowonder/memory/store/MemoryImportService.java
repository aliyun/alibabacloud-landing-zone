package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.memory.store.dto.MemoryImportCompletionRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryImportDecisionVO;
import com.aliyun.autowonder.memory.store.dto.MemoryImportManifestRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryImportSnapshotRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryImportClaimRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryImportClaimVO;
import com.aliyun.autowonder.mcp.DispatchMcpTokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;
import java.util.Date;
import java.util.HexFormat;
import java.util.Set;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MemoryImportService {
    static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final Set<String> PATHS = Set.of("AGENTS.md", "MEMORY.md", "memories.md");
    private static final Set<String> PROVIDERS = Set.of("qoder", "qodercn");
    private static final Set<String> OBSERVATION_STATUSES = Set.of("OBSERVED", "MISSING", "TOO_LARGE");
    private static final Pattern REDACTION_COUNT = Pattern.compile("\\\"redactionCount\\\"\\s*:\\s*(\\d+)");

    private final MemoryImportDao imports;
    private final MemoryStoreBootstrapService stores;

    public MemoryImportService(MemoryImportDao imports, MemoryStoreBootstrapService stores) {
        this.imports = imports;
        this.stores = stores;
    }

    @Transactional
    public MemoryImportDecisionVO negotiate(DispatchMcpTokenService.DispatchPrincipal principal,
                                             MemoryImportManifestRequest request) {
        requireManifest(request);
        long tenantId = principal.workspaceId();
        long agentId = principal.agentId();
        MemoryStoreDO target = stores.getOrCreate(tenantId, "AGENT", agentId,
                "Agent " + agentId + " memory", principal.userId());
        MemoryImportSourceDO source = new MemoryImportSourceDO();
        source.setTenantId(tenantId);
        source.setAgentId(agentId);
        source.setTargetStoreId(target.getId());
        source.setProviderFamily(request.providerFamily());
        source.setLogicalPath(request.logicalPath());
        source.setInstallationFingerprint(request.installationFingerprint());
        source.setLastObservedSanitizedSha256("OBSERVED".equals(request.observationStatus())
                ? request.sanitizedContentSha256() : null);
        source.setLastSeenAt(Date.from(Instant.now()));
        source.setStatus(request.observationStatus());
        imports.upsertSource(source);
        MemoryImportSourceDO persisted = imports.findSource(tenantId, agentId, request.providerFamily(),
                request.logicalPath(), request.installationFingerprint());
        if (persisted != null) source = persisted;
        if ("PAUSED".equals(source.getStatus()) || "RETIRED".equals(source.getStatus())) {
            return new MemoryImportDecisionVO(false, source.getId(), "SOURCE_" + source.getStatus(), false, null);
        }
        if ("RECURATE_REQUESTED".equals(source.getStatus())) {
            List<MemoryImportSnapshotDO> snapshots = imports.listLatestSnapshots(tenantId, source.getId());
            if (snapshots != null && !snapshots.isEmpty()) {
                MemoryImportSnapshotDO current = snapshots.get(0);
                imports.markSnapshotStatus(tenantId, current.getId(), "QUEUED");
                return new MemoryImportDecisionVO(false, source.getId(), "RECURATION_REQUIRED", true,
                        current.getSanitizedContentSha256());
            }
        }
        if ("MISSING".equals(request.observationStatus()) || "TOO_LARGE".equals(request.observationStatus())) {
            return new MemoryImportDecisionVO(false, source.getId(), "SOURCE_" + request.observationStatus(), false, null);
        }
        MemoryImportReceiptDO duplicate = imports.findSuccessfulReceiptByHash(
                tenantId, agentId, request.sanitizedContentSha256());
        if (duplicate != null) {
            return new MemoryImportDecisionVO(false, source.getId(), "UNCHANGED_SANITIZED_CONTENT", false,
                    request.sanitizedContentSha256());
        }
        MemoryImportSnapshotDO queued = imports.findSnapshotByHash(tenantId, source.getId(),
                request.sanitizedContentSha256());
        return new MemoryImportDecisionVO(queued == null, source.getId(),
                queued == null ? "UPLOAD_REQUIRED" : "CURATION_QUEUED", true,
                request.sanitizedContentSha256());
    }

    @Transactional
    public MemoryImportClaimVO claim(DispatchMcpTokenService.DispatchPrincipal principal,
                                     MemoryImportClaimRequest request) {
        if (request == null || request.sourceId() <= 0 || request.sanitizedContentSha256() == null
                || !request.sanitizedContentSha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("valid memory import claim is required");
        }
        long tenantId = principal.workspaceId();
        MemoryImportSourceDO source = requiredSource(principal, request.sourceId());
        MemoryImportSnapshotDO snapshot = imports.findSnapshotByHash(tenantId, source.getId(),
                request.sanitizedContentSha256());
        if (snapshot == null) throw new IllegalArgumentException("memory import snapshot not found");
        Instant now = Instant.now();
        String leaseId = UUID.randomUUID().toString();
        if (imports.claimSnapshot(tenantId, snapshot.getId(), leaseId, Date.from(now),
                Date.from(now.plusSeconds(600))) != 1) {
            return MemoryImportClaimVO.unavailable(source.getId(), request.sanitizedContentSha256());
        }
        String previous = null;
        List<MemoryImportSnapshotDO> latest = imports.listLatestSnapshots(tenantId, source.getId());
        if (latest != null) {
            for (MemoryImportSnapshotDO candidate : latest) {
                if (!candidate.getId().equals(snapshot.getId())) {
                    previous = candidate.getSanitizedContent();
                    break;
                }
            }
        }
        return new MemoryImportClaimVO(true, source.getId(), snapshot.getSanitizedContentSha256(), leaseId,
                snapshot.getSanitizedContent(), previous, redactionCount(snapshot.getScanSummaryJson()));
    }

    @Transactional
    public boolean renew(DispatchMcpTokenService.DispatchPrincipal principal, MemoryImportClaimRequest request) {
        MemoryImportSnapshotDO snapshot = requiredClaimedSnapshot(principal, request);
        Instant now = Instant.now();
        return imports.renewSnapshot(principal.workspaceId(), snapshot.getId(), request.leaseId(),
                Date.from(now), Date.from(now.plusSeconds(600))) == 1;
    }

    @Transactional
    public void release(DispatchMcpTokenService.DispatchPrincipal principal, MemoryImportClaimRequest request) {
        if (request == null || request.sourceId() <= 0 || request.sanitizedContentSha256() == null
                || !request.sanitizedContentSha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("valid memory import release is required");
        }
        MemoryImportSourceDO source = requiredSource(principal, request.sourceId());
        MemoryImportSnapshotDO snapshot = imports.findSnapshotByHash(principal.workspaceId(), source.getId(),
                request.sanitizedContentSha256());
        if (snapshot == null) throw new IllegalArgumentException("memory import snapshot not found");
        requireLeaseId(request.leaseId());
        imports.releaseSnapshot(principal.workspaceId(), snapshot.getId(), request.leaseId());
    }

    @Transactional
    public void upload(DispatchMcpTokenService.DispatchPrincipal principal,
                       MemoryImportSnapshotRequest request) {
        if (request == null || request.sourceId() <= 0 || request.sanitizedContent() == null
                || request.byteSize() < 0 || request.byteSize() > MAX_BYTES || request.redactionCount() < 0) {
            throw new IllegalArgumentException("valid sanitized import snapshot is required");
        }
        long tenantId = principal.workspaceId();
        MemoryImportSourceDO source = requiredSource(principal, request.sourceId());
        byte[] body = request.sanitizedContent().getBytes(StandardCharsets.UTF_8);
        String actual = sha256(body);
        if (body.length != request.byteSize() || !actual.equals(request.sanitizedContentSha256())) {
            throw new IllegalArgumentException("sanitized import snapshot hash or size mismatch");
        }
        if (!"RECURATE_REQUESTED".equals(source.getStatus())
                && imports.findSuccessfulReceiptByHash(tenantId, principal.agentId(), actual) != null) {
            return;
        }
        MemoryImportSnapshotDO snapshot = imports.findSnapshotByHash(tenantId, source.getId(), actual);
        if (snapshot == null) {
            snapshot = new MemoryImportSnapshotDO();
            snapshot.setTenantId(tenantId);
            snapshot.setSourceId(source.getId());
            snapshot.setSanitizedContentSha256(actual);
            snapshot.setSanitizedContent(request.sanitizedContent());
            snapshot.setByteSize((long) body.length);
            snapshot.setScanSummaryJson("{\"sanitizedLocally\":true,\"redactionCount\":"
                    + request.redactionCount() + "}");
            snapshot.setStatus("QUEUED");
            imports.insertSnapshot(snapshot);
        }
        imports.deleteSnapshotsBeforeLatestTwo(tenantId, source.getId());
        imports.markSnapshotStatus(tenantId, snapshot.getId(), "QUEUED");
    }

    @Transactional
    public void complete(DispatchMcpTokenService.DispatchPrincipal principal,
                         MemoryImportCompletionRequest request) {
        if (request == null || request.sourceId() <= 0 || request.sanitizedContentSha256() == null
                || !request.sanitizedContentSha256().matches("[0-9a-f]{64}") || request.redactionCount() < 0) {
            throw new IllegalArgumentException("valid memory import completion is required");
        }
        long tenantId = principal.workspaceId();
        MemoryImportSourceDO source = requiredSource(principal, request.sourceId());
        MemoryImportSnapshotDO snapshot = imports.findSnapshotByHash(
                tenantId, source.getId(), request.sanitizedContentSha256());
        if (snapshot == null) throw new IllegalArgumentException("memory import snapshot not found");
        requireLeaseId(request.leaseId());
        if (imports.completeSnapshot(tenantId, snapshot.getId(), request.leaseId(), new Date()) != 1) {
            throw new IllegalArgumentException("memory import lease is stale");
        }
        imports.markSourceImported(tenantId, source.getId(), request.sanitizedContentSha256());
        MemoryImportReceiptDO receipt = new MemoryImportReceiptDO();
        receipt.setTenantId(tenantId);
        receipt.setAgentId(principal.agentId());
        receipt.setSourceId(source.getId());
        receipt.setSnapshotId(snapshot.getId());
        receipt.setSanitizedContentSha256(request.sanitizedContentSha256());
        receipt.setCurationDispatchId(principal.dispatchId());
        receipt.setOutcome("SUCCEEDED");
        receipt.setTargetVersionsJson("{\"storeId\":" + source.getTargetStoreId()
                + ",\"acknowledged\":true}");
        receipt.setRedactionCount(request.redactionCount());
        imports.upsertReceipt(receipt);
    }

    private MemoryImportSnapshotDO requiredClaimedSnapshot(
            DispatchMcpTokenService.DispatchPrincipal principal, MemoryImportClaimRequest request) {
        if (request == null || request.sourceId() <= 0 || request.sanitizedContentSha256() == null
                || !request.sanitizedContentSha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("valid memory import lease is required");
        }
        requireLeaseId(request.leaseId());
        MemoryImportSourceDO source = requiredSource(principal, request.sourceId());
        MemoryImportSnapshotDO snapshot = imports.findSnapshotByHash(principal.workspaceId(), source.getId(),
                request.sanitizedContentSha256());
        if (snapshot == null) throw new IllegalArgumentException("memory import snapshot not found");
        return snapshot;
    }

    private void requireLeaseId(String leaseId) {
        if (leaseId == null || !leaseId.matches("[0-9a-f-]{36}")) {
            throw new IllegalArgumentException("valid memory import lease is required");
        }
    }

    private MemoryImportSourceDO requiredSource(DispatchMcpTokenService.DispatchPrincipal principal, long sourceId) {
        MemoryImportSourceDO source = imports.findSourceById(principal.workspaceId(), sourceId);
        if (source == null || !principal.agentId().equals(source.getAgentId())) {
            throw new IllegalArgumentException("private memory import source not found");
        }
        if ("PAUSED".equals(source.getStatus()) || "RETIRED".equals(source.getStatus())) {
            throw new IllegalStateException("memory import source is " + source.getStatus().toLowerCase());
        }
        return source;
    }

    private void requireManifest(MemoryImportManifestRequest request) {
        if (request == null || !PROVIDERS.contains(request.providerFamily()) || !PATHS.contains(request.logicalPath())
                || request.installationFingerprint() == null || request.installationFingerprint().isBlank()
                || !OBSERVATION_STATUSES.contains(request.observationStatus())
                || ("OBSERVED".equals(request.observationStatus())
                    && (request.sanitizedContentSha256() == null
                        || !request.sanitizedContentSha256().matches("[0-9a-f]{64}")
                        || request.byteSize() < 0 || request.byteSize() > MAX_BYTES))) {
            throw new IllegalArgumentException("invalid legacy memory import manifest");
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private int redactionCount(String scanSummaryJson) {
        if (scanSummaryJson == null) return 0;
        Matcher matcher = REDACTION_COUNT.matcher(scanSummaryJson);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}
