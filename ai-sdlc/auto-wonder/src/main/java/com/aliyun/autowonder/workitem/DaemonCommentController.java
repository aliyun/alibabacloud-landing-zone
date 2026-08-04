package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import com.aliyun.autowonder.workitem.dto.WorkitemVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/daemon")
public class DaemonCommentController {

    private static final Logger log = LoggerFactory.getLogger(DaemonCommentController.class);

    private final DaemonUploadAuthenticator authenticator;
    private final WorkitemService workitemService;
    private final AuditLogService auditLogService;
    private final GuidanceService guidanceService;

    public DaemonCommentController(DaemonUploadAuthenticator authenticator, WorkitemService workitemService,
            AuditLogService auditLogService, GuidanceService guidanceService) {
        this.authenticator = authenticator;
        this.workitemService = workitemService;
        this.auditLogService = auditLogService;
        this.guidanceService = guidanceService;
    }

    @PostMapping("/dispatches/{dispatchId}/comments")
    public ResponseEntity<?> comment(@PathVariable long dispatchId,
                                     @RequestParam("token") String token,
                                     @RequestBody Map<String, String> body) {
        DaemonUploadAuthenticator.AuthResult auth = authenticator.authenticate(dispatchId, token);
        if (!auth.isSuccess()) {
            return ResponseEntity.status(401).build();
        }
        if (auth.isInteractionDispatch()) {
            return interactionMutationConflict();
        }
        String contentMd = body.get("contentMd");
        if (contentMd == null || contentMd.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "contentMd required"));
        }
        CommentVO vo = workitemService.addAgentComment(
                auth.getWorkitemId(), contentMd, auth.getTenantId(), auth.getAgentId());
        guidanceService.createForComment(auth.getTenantId(), auth.getWorkitemId(), vo.getId(), contentMd,
                null, auth.getAgentId());
        log.info("agent comment added dispatchId={} workitemId={} agentId={}",
                dispatchId, auth.getWorkitemId(), auth.getAgentId());
        AuditLogRecord audit = daemonAuditRecord(auth, dispatchId, "CREATE_WORKITEM_COMMENT", "daemon.comment");
        audit.detail("contentLength", contentMd.length());
        auditLogService.record(audit);
        return ResponseEntity.ok(vo);
    }

    @PostMapping("/dispatches/{dispatchId}/workitem-status")
    public ResponseEntity<?> workitemStatus(@PathVariable long dispatchId,
                                            @RequestParam("token") String token,
                                            @RequestBody Map<String, String> body) {
        DaemonUploadAuthenticator.AuthResult auth = authenticator.authenticate(dispatchId, token);
        if (!auth.isSuccess()) {
            return ResponseEntity.status(401).build();
        }
        if (auth.isInteractionDispatch()) {
            return interactionMutationConflict();
        }
        String status = body.get("status");
        if (status == null || status.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "status required"));
        }
        WorkitemVO vo = workitemService.agentTransition(
                auth.getWorkitemId(), status, auth.getTenantId(), auth.getAgentId());
        log.info("agent workitem-status changed dispatchId={} workitemId={} agentId={} status={}",
                dispatchId, auth.getWorkitemId(), auth.getAgentId(), status);
        AuditLogRecord audit = daemonAuditRecord(auth, dispatchId, "UPDATE_WORKITEM_STATUS", "daemon.workitem-status");
        audit.detail("status", status);
        auditLogService.record(audit);
        return ResponseEntity.ok(vo);
    }

    private ResponseEntity<?> interactionMutationConflict() {
        return ResponseEntity.status(409).body(Map.of(
                "error", "interaction dispatch replies are delivered through TASK_GUIDANCE_ACK"));
    }

    private AuditLogRecord daemonAuditRecord(DaemonUploadAuthenticator.AuthResult auth, long dispatchId,
            String action, String eventType) {
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(auth.getTenantId());
        record.setActorId(auth.getAgentId());
        record.setActorType("AGENT");
        record.setModule("WORKITEM");
        record.setAction(action);
        record.setTargetType("workitem");
        record.setTargetId(auth.getWorkitemId());
        record.setTriggerType("EVENT");
        record.setTriggerSource("DAEMON_CALLBACK");
        record.setEventType(eventType);
        record.detail("dispatchId", dispatchId);
        return record;
    }
}
