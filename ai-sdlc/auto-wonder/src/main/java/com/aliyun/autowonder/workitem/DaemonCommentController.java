package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunCommentService;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDao;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import com.aliyun.autowonder.workitem.dto.WorkitemVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/daemon")
public class DaemonCommentController {

    private static final Logger log = LoggerFactory.getLogger(DaemonCommentController.class);

    private final DaemonUploadAuthenticator authenticator;
    private final WorkitemService workitemService;
    private final AuditLogService auditLogService;
    private final GuidanceService guidanceService;
    private final ScheduledTaskRunCommentService runCommentService;
    private final ScheduledTaskCapabilityGuard capabilityGuard;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ScheduledTaskRunDao scheduledTaskRunDao;

    DaemonCommentController(DaemonUploadAuthenticator authenticator, WorkitemService workitemService,
            AuditLogService auditLogService, GuidanceService guidanceService) {
        this(authenticator, workitemService, auditLogService, guidanceService, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DaemonCommentController(DaemonUploadAuthenticator authenticator, WorkitemService workitemService,
            AuditLogService auditLogService, GuidanceService guidanceService,
            ScheduledTaskRunCommentService runCommentService,
            ScheduledTaskCapabilityGuard capabilityGuard) {
        this.authenticator = authenticator;
        this.workitemService = workitemService;
        this.auditLogService = auditLogService;
        this.guidanceService = guidanceService;
        this.runCommentService = runCommentService;
        this.capabilityGuard = capabilityGuard;
    }

    DaemonCommentController(DaemonUploadAuthenticator authenticator, WorkitemService workitemService,
            AuditLogService auditLogService, GuidanceService guidanceService,
            ScheduledTaskCapabilityGuard capabilityGuard) {
        this(authenticator, workitemService, auditLogService, guidanceService, null, capabilityGuard);
    }

    @PostMapping("/dispatches/{dispatchId}/comments")
    public ResponseEntity<?> comment(@PathVariable long dispatchId,
                                     @RequestParam("token") String token,
                                     @RequestBody Map<String, Object> body) {
        DaemonUploadAuthenticator.AuthResult auth = authenticator.authenticate(dispatchId, token);
        if (!auth.isSuccess()) {
            return ResponseEntity.status(401).build();
        }
        requireScheduledCapability(auth);
        if (auth.isInteractionDispatch()) {
            return interactionMutationConflict();
        }
        Object rawContent = body == null ? null : body.get("contentMd");
        String contentMd = rawContent instanceof String value ? value : null;
        if (contentMd == null || contentMd.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "contentMd required"));
        }
        List<Long> targetHumanIds = parseTargetHumanIds(body.get("targetHumanIds"));
        CommentVO vo;
        if (auth.getSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN) {
            if (runCommentService == null) return ResponseEntity.status(503).build();
            vo = runCommentService.addAgentComment(auth.getTenantId(), auth.getWorkitemId(), auth.getAgentId(),
                    contentMd, List.of(), targetHumanIds);
        } else if (auth.getSourceType() == ExecutionSourceType.WORKITEM) {
            vo = workitemService.addAgentComment(auth.getWorkitemId(), contentMd, targetHumanIds,
                    auth.getTenantId(), auth.getAgentId());
            guidanceService.createForComment(auth.getTenantId(), auth.getWorkitemId(), vo.getId(), contentMd,
                    null, auth.getAgentId());
        } else {
            return ResponseEntity.status(409).build();
        }
        log.info("agent comment added dispatchId={} workitemId={} agentId={}",
                dispatchId, auth.getWorkitemId(), auth.getAgentId());
        AuditLogRecord audit = daemonAuditRecord(auth, dispatchId, "CREATE_WORKITEM_COMMENT", "daemon.comment");
        audit.detail("contentLength", contentMd.length());
        auditLogService.record(audit);
        return ResponseEntity.ok(vo);
    }

    private static List<Long> parseTargetHumanIds(Object raw) {
        if (!(raw instanceof Collection<?> values)) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Number number) {
                result.add(number.longValue());
            }
        }
        return result;
    }

    @PostMapping("/dispatches/{dispatchId}/workitem-status")
    public ResponseEntity<?> workitemStatus(@PathVariable long dispatchId,
                                            @RequestParam("token") String token,
                                            @RequestBody Map<String, String> body) {
        DaemonUploadAuthenticator.AuthResult auth = authenticator.authenticate(dispatchId, token);
        if (!auth.isSuccess()) {
            return ResponseEntity.status(401).build();
        }
        requireScheduledCapability(auth);
        if (auth.getSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "scheduled task runs do not support workitem status mutation"));
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

    private void requireScheduledCapability(DaemonUploadAuthenticator.AuthResult auth) {
        if (auth.getSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN) {
            capabilityGuard.requireAvailable("daemon");
        }
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
        record.setModule(auth.getSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN ? "SCHEDULED_TASK" : "WORKITEM");
        record.setAction(action);
        record.setTargetType(auth.getSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN ? "scheduled_task_run" : "workitem");
        record.setTargetId(auth.getWorkitemId());
        record.setTriggerType("EVENT");
        record.setTriggerSource("DAEMON_CALLBACK");
        record.setEventType(eventType);
        record.detail("dispatchId", dispatchId);
        record.detail("sourceType", auth.getSourceType().name());
        if (auth.getSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN) {
            record.detail("runId", auth.getWorkitemId());
            var run = scheduledTaskRunDao == null ? null : scheduledTaskRunDao.findById(auth.getTenantId(), auth.getWorkitemId());
            if (run != null) record.detail("taskId", run.getScheduledTaskId());
        }
        return record;
    }
}
