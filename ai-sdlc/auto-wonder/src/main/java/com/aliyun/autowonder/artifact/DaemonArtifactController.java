package com.aliyun.autowonder.artifact;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.aiusage.DispatchAiUsageService;
import com.aliyun.autowonder.artifact.dto.ReportArtifactRequest;
import com.aliyun.autowonder.evolution.EvolutionDeltaIngestionLiteService;
import com.aliyun.autowonder.evolution.EvolutionMode;
import com.aliyun.autowonder.evolution.EvolutionModeResolverLiteService;
import com.aliyun.autowonder.memory.MemorySedimentationService;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.storage.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/daemon")
public class DaemonArtifactController {

    private static final Logger log = LoggerFactory.getLogger(DaemonArtifactController.class);
    private static final int MAX_FILES_PER_UPLOAD = 200;
    private static final long MAX_SINGLE_FILE_BYTES = 50 * 1024 * 1024; // 50 MB

    private final DaemonUploadAuthenticator authenticator;
    private final ObjectStorage storage;
    private final ArtifactService artifactService;
    private final MemorySedimentationService memorySedimentation;
    private final EvolutionDeltaIngestionLiteService evolutionDeltaIngestion;
    private final EvolutionModeResolverLiteService evolutionModeResolver;
    private final AuditLogService auditLogService;
    private final DispatchAiUsageService usageService;
    private final String artifactBucket;

    public DaemonArtifactController(DaemonUploadAuthenticator authenticator,
                                    ObjectStorage storage,
                                    ArtifactService artifactService,
                                    MemorySedimentationService memorySedimentation,
                                    EvolutionDeltaIngestionLiteService evolutionDeltaIngestion,
                                    EvolutionModeResolverLiteService evolutionModeResolver,
                                    AuditLogService auditLogService,
                                    DispatchAiUsageService usageService,
                                    OssProperties ossProperties) {
        this.authenticator = authenticator;
        this.storage = storage;
        this.artifactService = artifactService;
        this.memorySedimentation = memorySedimentation;
        this.evolutionDeltaIngestion = evolutionDeltaIngestion;
        this.evolutionModeResolver = evolutionModeResolver;
        this.auditLogService = auditLogService;
        this.usageService = usageService;
        this.artifactBucket = ossProperties.resolveArtifactBucket();
    }

    @PostMapping("/dispatches/{dispatchId}/artifacts")
    public ResponseEntity<?> upload(
            @PathVariable long dispatchId,
            @RequestParam("token") String token,
            @RequestParam(value = "idempotencyKey", required = false) String idempotencyKey,
            @RequestParam(value = "filesMetadata", required = false) String filesMetadata,
            @RequestParam("files") MultipartFile[] files) throws Exception {

        log.info("artifact upload request dispatchId={} fileCount={}", dispatchId, files.length);
        DaemonUploadAuthenticator.AuthResult auth = authenticator.authenticate(dispatchId, token);
        if (!auth.isSuccess()) {
            log.info("artifact upload auth failed dispatchId={}", dispatchId);
            return ResponseEntity.status(401).build();
        }
        EvolutionMode evolutionMode = evolutionModeResolver.resolve(auth.getTenantId(), auth.getAgentId());

        JSONArray metadata = null;
        if (filesMetadata != null && !filesMetadata.isBlank()) {
            try {
                metadata = JSON.parseArray(filesMetadata);
            } catch (Exception e) {
                log.warn("invalid filesMetadata JSON for dispatchId={}", dispatchId);
            }
        }

        if (files.length > MAX_FILES_PER_UPLOAD) {
            return ResponseEntity.badRequest().body(Map.of("error", "too many files"));
        }

        String prefix = "t/" + auth.getTenantId() + "/workitem/" + auth.getWorkitemId()
                + "/dispatch/" + dispatchId + "/";
        List<Map<String, Object>> fileReceipts = new ArrayList<>(files.length);

        for (int i = 0; i < files.length; i++) {
            String requestedPath = resolveFilePath(metadata, i, files[i].getOriginalFilename());
            String path = sanitizePath(requestedPath);
            if (path == null) {
                log.warn("rejected unsafe file path in dispatch={} index={}", dispatchId, i);
                fileReceipts.add(rejectedFile(i, requestedPath, files[i].getSize(), "INVALID_PATH", null));
                continue;
            }
            if (files[i].getSize() > MAX_SINGLE_FILE_BYTES) {
                log.warn("rejected oversized file dispatch={} path={} size={}", dispatchId, path, files[i].getSize());
                fileReceipts.add(rejectedFile(i, path, files[i].getSize(), "FILE_TOO_LARGE", MAX_SINGLE_FILE_BYTES));
                continue;
            }
            byte[] bytes = files[i].getBytes();

            // Daemon uploads under the artifact root (e.g. artifacts/output/<typed-dir>/...);
            // typed-dir classification and memory ingest match on the logical path.
            String logical = logicalPath(path);
            StoredObject so;
            try {
                so = storage.put(artifactBucket, prefix + path, bytes);
            } catch (RuntimeException uploadFailure) {
                log.error("artifact upload unavailable dispatchId={}", dispatchId, uploadFailure);
                return ResponseEntity.status(503).body(Map.of("error", "artifact upload temporarily unavailable"));
            }
            String artifactType = isTelemetry(logical) ? "TELEMETRY" : classify(logical);
            log.info("artifact uploaded dispatchId={} path={} type={} size={}", dispatchId, path, artifactType, files[i].getSize());
            ReportArtifactRequest req = new ReportArtifactRequest();
            req.setWorkitemId(auth.getWorkitemId());
            req.setDispatchId(dispatchId);
            req.setName(path);
            req.setType(artifactType);
            req.setOssRef(so.getOssRef());
            req.setSize(so.getSize());
            Long artifactId = artifactService.record(req, auth.getTenantId());
            usageService.ingestArtifact(auth.getTenantId(), auth.getWorkitemId(), dispatchId, artifactId, path, so.getOssRef(), bytes);
            if (!isTelemetry(logical)) {
                recordArtifactAudit(auth, dispatchId, path, classify(logical), so.getSize());
            }
            Map<String, Object> accepted = new LinkedHashMap<>();
            accepted.put("index", i);
            accepted.put("path", path);
            accepted.put("status", "ACCEPTED");
            accepted.put("sizeBytes", files[i].getSize());
            accepted.put("ossRef", so.getOssRef());
            fileReceipts.add(accepted);

            if (evolutionMode.acceptsRuntimeDelta() && "learning_delta/memory_delta.json".equals(logical)) {
                log.info("artifact memory_delta ingested dispatchId={} agentId={}", dispatchId, auth.getAgentId());
                memorySedimentation.ingest(auth.getTenantId(), auth.getAgentId(), dispatchId, bytes);
            }
            if (evolutionMode.acceptsRuntimeDelta() && "learning_delta/evolution_delta.json".equals(logical)) {
                try {
                    evolutionDeltaIngestion.ingest(auth.getTenantId(), auth.getAgentId(), dispatchId, bytes, evolutionMode);
                    log.info("artifact evolution_delta ingested dispatchId={} agentId={}", dispatchId, auth.getAgentId());
                } catch (RuntimeException ex) {
                    log.warn("artifact evolution_delta ingestion skipped dispatchId={} agentId={}",
                            dispatchId, auth.getAgentId(), ex);
                }
            }
        }

        return ResponseEntity.ok(Map.of("remoteRef", prefix, "files", fileReceipts));
    }

    private Map<String, Object> rejectedFile(int index, String path, long size, String code, Long maxBytes) {
        Map<String, Object> rejected = new LinkedHashMap<>();
        rejected.put("index", index);
        rejected.put("path", path == null ? "" : path);
        rejected.put("status", "REJECTED");
        rejected.put("code", code);
        rejected.put("sizeBytes", size);
        if (maxBytes != null) {
            rejected.put("maxBytes", maxBytes);
        }
        return rejected;
    }

    private void recordArtifactAudit(DaemonUploadAuthenticator.AuthResult auth, long dispatchId,
            String path, String type, long size) {
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(auth.getTenantId());
        record.setActorId(auth.getAgentId());
        record.setActorType("AGENT");
        record.setModule("ARTIFACT");
        record.setAction("UPLOAD_ARTIFACT");
        record.setTargetType("dispatch");
        record.setTargetId(dispatchId);
        record.setTriggerType("EVENT");
        record.setTriggerSource("DAEMON_CALLBACK");
        record.setEventType("daemon.artifact");
        record.detail("workitemId", auth.getWorkitemId())
                .detail("path", path)
                .detail("type", type)
                .detail("size", size);
        auditLogService.record(record);
    }

    private String resolveFilePath(JSONArray metadata, int index, String fallback) {
        if (metadata != null && index < metadata.size()) {
            JSONObject entry = metadata.getJSONObject(index);
            if (entry != null) {
                String path = entry.getString("path");
                if (path != null && !path.isBlank()) {
                    return path;
                }
            }
        }
        return fallback != null ? fallback : "file_" + index;
    }

    static String sanitizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.contains("\0")) {
            return null;
        }
        String normalized = raw.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")
                || normalized.startsWith("..")) {
            return null;
        }
        if (normalized.contains("/../") || normalized.endsWith("/..")) {
            return null;
        }
        return normalized;
    }

    /**
     * Strips the daemon's artifact-root prefix so typed-dir classification and
     * memory ingest match on the logical path (e.g. learning_delta/memory_delta.json).
     * The daemon uploads every artifact under artifacts/output/; older/bare paths pass through.
     */
    static String logicalPath(String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith("artifacts/output/")) {
            return path.substring("artifacts/output/".length());
        }
        if (path.startsWith("output/")) {
            return path.substring("output/".length());
        }
        return path;
    }

    static String classify(String path) {
        if (path.startsWith("deliverables/")) return "DELIVERABLE";
        if (path.startsWith("patches/")) return "PATCH";
        if (path.startsWith("evidence/")) return "EVIDENCE";
        if (path.startsWith("handoff/")) return "HANDOFF";
        if (path.startsWith("learning_delta/")) return "LEARNING";
        return "FILE";
    }

    static boolean isTelemetry(String logicalPath) {
        return logicalPath != null && (logicalPath.startsWith("observability/")
                || logicalPath.contains("/observability/"));
    }
}
