package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/daemon/dispatches")
public class DaemonCheckpointController {

    private static final long MAX_CHECKPOINT_BYTES = 50L * 1024 * 1024;

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
