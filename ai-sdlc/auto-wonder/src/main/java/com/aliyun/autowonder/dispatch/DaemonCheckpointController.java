package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/daemon/dispatches")
public class DaemonCheckpointController {

    private static final long MAX_CHECKPOINT_BYTES = 50L * 1024 * 1024;
    private static final Set<String> REFRESHABLE_STATUSES = Set.of(
            DispatchStatus.DISPATCHED,
            DispatchStatus.ACKED,
            DispatchStatus.RUNNING);

    private final DaemonUploadAuthenticator authenticator;
    private final DispatchDao dispatchDao;
    private final DispatchCheckpointService checkpointService;
    private final AuditLogService auditLogService;

    public DaemonCheckpointController(DaemonUploadAuthenticator authenticator,
            DispatchDao dispatchDao, DispatchCheckpointService checkpointService,
            AuditLogService auditLogService) {
        this.authenticator = authenticator;
        this.dispatchDao = dispatchDao;
        this.checkpointService = checkpointService;
        this.auditLogService = auditLogService;
    }

    public ResponseEntity<?> upload(long dispatchId, String token, long checkpointSeq,
            String provider, String providerSessionId, String runtimeId, String activeStepId,
            MultipartFile checkpoint) throws Exception {
        return upload(dispatchId, token, checkpointSeq, provider, providerSessionId, runtimeId,
                activeStepId, checkpoint, null, null);
    }

    @PostMapping("/{dispatchId}/checkpoint")
    public ResponseEntity<?> upload(@PathVariable long dispatchId,
            @RequestParam("token") String token,
            @RequestParam("checkpointSeq") long checkpointSeq,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "providerSessionId", required = false) String providerSessionId,
            @RequestParam(value = "runtimeId", required = false) String runtimeId,
            @RequestParam(value = "activeStepId", required = false) String activeStepId,
            @RequestParam("checkpoint") MultipartFile checkpoint,
            @RequestParam(value = "purpose", required = false) String purpose,
            @RequestParam(value = "resultId", required = false) String resultId) throws Exception {
        DaemonUploadAuthenticator.AuthResult auth = authenticator.authenticate(dispatchId, token);
        if (!auth.isSuccess()) {
            return ResponseEntity.status(401).build();
        }
        if (checkpointSeq <= 0 || checkpoint.isEmpty() || checkpoint.getSize() > MAX_CHECKPOINT_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid checkpoint"));
        }
        boolean completion = "COMPLETION".equals(purpose);
        if ((purpose != null && !completion && !"RECOVERY".equals(purpose))
                || (completion && !DispatchResultCoordinator.validResultId(resultId))
                || (!completion && resultId != null)) {
            return ResponseEntity.badRequest().body(Map.of("error", com.aliyun.autowonder.common.i18n.SystemMessages.render(
                    "dispatch.result.invalid_identity", new Object[0], "invalid completion identity")));
        }
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        DispatchCheckpointDO stored;
        try {
            stored = completion
                    ? checkpointService.store(dispatch, checkpointSeq, provider, providerSessionId,
                            runtimeId, activeStepId, checkpoint.getBytes(), resultId)
                    : checkpointService.store(dispatch, checkpointSeq,
                            provider, providerSessionId, runtimeId, activeStepId, checkpoint.getBytes());
        } catch (DispatchCheckpointService.CheckpointConflictException conflict) {
            return ResponseEntity.status(409).body(Map.of("error", com.aliyun.autowonder.common.i18n.SystemMessages.render(
                    "dispatch.result.checkpoint_conflict", new Object[0], "checkpoint identity conflict")));
        } catch (RuntimeException uploadFailure) {
            return ResponseEntity.status(503).body(Map.of("error", "checkpoint upload temporarily unavailable"));
        }
        recordCheckpointAudit(auth, dispatchId, checkpointSeq, provider, providerSessionId, runtimeId, activeStepId,
                stored.getSizeBytes());
        if (completion) return ResponseEntity.ok(Map.of(
                "checkpointSeq", stored.getCheckpointSeq(), "sha256", "sha256:" + stored.getSha256(),
                "sizeBytes", stored.getSizeBytes(), "purpose", "COMPLETION", "resultId", stored.getCompletionResultId()));
        return ResponseEntity.ok(Map.of(
                "checkpointSeq", stored.getCheckpointSeq(),
                "sha256", "sha256:" + stored.getSha256(),
                "sizeBytes", stored.getSizeBytes()));
    }

    /**
     * Re-signs the download link of one checkpoint this dispatch is allowed to
     * restore. The executor calls it before the real download, so a link that
     * expired while the task package was still being prepared is never fatal.
     */
    @PostMapping("/{dispatchId}/checkpoint-url")
    public ResponseEntity<?> refreshDownloadUrl(@PathVariable long dispatchId,
            @RequestParam("token") String token,
            @RequestParam("checkpointSeq") long checkpointSeq) {
        DaemonUploadAuthenticator.AuthResult auth = authenticator.authenticate(dispatchId, token);
        if (!auth.isSuccess()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null) {
            return checkpointError(HttpStatus.NOT_FOUND, "daemon.checkpoint.url.not_found",
                    "checkpoint not found");
        }
        if (checkpointSeq <= 0) {
            return checkpointError(HttpStatus.BAD_REQUEST, "daemon.checkpoint.url.invalid_request",
                    "invalid checkpoint identity");
        }
        if (!REFRESHABLE_STATUSES.contains(dispatch.getStatus())) {
            return checkpointError(HttpStatus.CONFLICT, "daemon.checkpoint.url.not_refreshable",
                    "checkpoint URL is not refreshable");
        }
        CheckpointDownloadLink link;
        try {
            link = checkpointService.refreshDownloadLink(dispatch, checkpointSeq);
        } catch (DispatchCheckpointService.CheckpointObjectMissingException missingObject) {
            return checkpointError(HttpStatus.GONE, "daemon.checkpoint.url.object_missing",
                    "checkpoint object not found");
        } catch (DispatchCheckpointService.CheckpointNotRestorableException notRestorable) {
            return checkpointError(HttpStatus.FORBIDDEN, "daemon.checkpoint.url.not_restorable",
                    "checkpoint is not a restore candidate");
        } catch (DispatchCheckpointService.CheckpointInvalidRequestException invalidRequest) {
            return checkpointError(HttpStatus.BAD_REQUEST, "daemon.checkpoint.url.invalid_request",
                    "invalid checkpoint identity");
        } catch (DispatchCheckpointService.CheckpointNotFoundException notFound) {
            return checkpointError(HttpStatus.NOT_FOUND, "daemon.checkpoint.url.not_found",
                    "checkpoint not found");
        }
        recordCheckpointRefreshAudit(auth, dispatchId, checkpointSeq);
        return ResponseEntity.ok(Map.of(
                "checkpointSeq", link.checkpointSeq(),
                "downloadUrl", link.downloadUrl(),
                "expiresAt", link.expiresAt(),
                "expiresInSeconds", link.expiresInSeconds(),
                "sha256", link.sha256()));
    }

    private ResponseEntity<?> checkpointError(HttpStatus status, String key, String diagnostic) {
        return ResponseEntity.status(status).body(Map.of(
                "code", key,
                "error", com.aliyun.autowonder.common.i18n.SystemMessages.render(
                        key, new Object[0], diagnostic)));
    }

    private void recordCheckpointRefreshAudit(DaemonUploadAuthenticator.AuthResult auth,
            long dispatchId, long checkpointSeq) {
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(auth.getTenantId());
        record.setActorId(auth.getAgentId());
        record.setActorType("AGENT");
        record.setModule("DISPATCH");
        record.setAction("REFRESH_CHECKPOINT_URL");
        record.setTargetType("dispatch");
        record.setTargetId(dispatchId);
        record.setTriggerType("EVENT");
        record.setTriggerSource("DAEMON_CALLBACK");
        record.setEventType("daemon.checkpoint-url");
        record.detail("workitemId", auth.getWorkitemId())
                .detail("checkpointSeq", checkpointSeq);
        auditLogService.record(record);
    }

    private void recordCheckpointAudit(DaemonUploadAuthenticator.AuthResult auth, long dispatchId,
            long checkpointSeq, String provider, String providerSessionId, String runtimeId,
            String activeStepId, long sizeBytes) {
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(auth.getTenantId());
        record.setActorId(auth.getAgentId());
        record.setActorType("AGENT");
        record.setModule("DISPATCH");
        record.setAction("UPLOAD_CHECKPOINT");
        record.setTargetType("dispatch");
        record.setTargetId(dispatchId);
        record.setTriggerType("EVENT");
        record.setTriggerSource("DAEMON_CALLBACK");
        record.setEventType("daemon.checkpoint");
        record.detail("workitemId", auth.getWorkitemId())
                .detail("checkpointSeq", checkpointSeq)
                .detail("provider", provider)
                .detail("providerSessionId", providerSessionId)
                .detail("runtimeId", runtimeId)
                .detail("activeStepId", activeStepId)
                .detail("sizeBytes", sizeBytes);
        auditLogService.record(record);
    }
}
