package com.aliyun.autowonder.scheduledtask;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.dispatch.HandoffResult;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.notification.NotifyEvent;
import com.aliyun.autowonder.notification.NotifyService;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.RequirementDocumentService;
import com.aliyun.autowonder.storage.ObjectStorage;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Starts an immutable scheduled Run by creating its root Dispatch on the existing delivery path. */
@Service
public class ScheduledTaskRunOrchestrator {
    private static final String SNAPSHOT_SCHEMA = "autowonder.scheduledTaskExecutionSnapshot.v1";
    private final ScheduledTaskRunDao runDao;
    private final DispatchService dispatchService;
    private UserDao userDao; private WorkspaceMemberDao workspaceMemberDao; private ScheduledTaskDao taskDao;
    private AuditLogService auditLogService; private NotifyService notifyService;
    private ArtifactDao artifactDao; private ObjectStorage storage;
    private ScheduledTaskRunRecoveryService recoveryService;
    private ScheduledTaskNotificationService notificationService;
    private ScheduledTaskMetrics metrics;
    private ScheduledTaskRunService runService;

    public ScheduledTaskRunOrchestrator(ScheduledTaskRunDao runDao, DispatchService dispatchService) {
        this.runDao = runDao;
        this.dispatchService = dispatchService;
    }
    @Autowired public void setOwnerDependencies(UserDao userDao, WorkspaceMemberDao workspaceMemberDao,
            ScheduledTaskDao taskDao, AuditLogService auditLogService, NotifyService notifyService) {
        this.userDao=userDao; this.workspaceMemberDao=workspaceMemberDao; this.taskDao=taskDao;
        this.auditLogService=auditLogService; this.notifyService=notifyService;
    }
    @Autowired public void setDocumentDependencies(ArtifactDao artifactDao, ObjectStorage storage) {
        this.artifactDao = artifactDao; this.storage = storage;
    }
    @Autowired public void setRecoveryService(@org.springframework.context.annotation.Lazy ScheduledTaskRunRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }
    @Autowired(required = false)
    public void setNotificationService(ScheduledTaskNotificationService notificationService) { this.notificationService = notificationService; }
    @Autowired(required = false)
    public void setMetrics(ScheduledTaskMetrics metrics) { this.metrics = metrics; }
    @Autowired public void setRunService(ScheduledTaskRunService runService) { this.runService = runService; }

    @Transactional(rollbackFor = Exception.class)
    public void start(long workspaceId, long runId, long actorId) {
        ScheduledTaskRunDO run = runDao.findById(workspaceId, runId);
        if (run == null || !Long.valueOf(workspaceId).equals(run.getWorkspaceId())
                || !("QUEUED".equals(run.getStatus()) || "STARTING".equals(run.getStatus()))) {
            return;
        }
        try {
            if (!ownerActive(run)) { ownerInactive(run, actorId); return; }
            JSONObject snapshot = parseAndValidate(run);
            Long sdlcId = run.getSdlcId();
            Long stepId = run.getCurrentStepId();
            if (snapshot.getJSONObject("sdlc") == null) {
                sdlcId = null;
                stepId = null;
            }
            String expectedStatus = run.getStatus();
            if (runDao.initializeExecution(workspaceId, runId, expectedStatus, sdlcId,
                    run.getInitialAgentId(), stepId, run.getVersion(), actorId) != 1) {
                return;
            }
            run = runDao.findById(workspaceId, runId);
            if (run == null || !"STARTING".equals(run.getStatus()) || run.getVersion() == null) {
                throw invalid("Run start state was not persisted");
            }
            runService.observePersistedTransition(run, null);
            long frozenVersion = frozenVersion(snapshot, run.getInitialAgentId());
            if (metrics != null && run.getGmtCreate() != null) metrics.queueWait(System.currentTimeMillis() - run.getGmtCreate().getTime());
            ScheduledTaskRunRecoveryService.ResumePlan resume = recoveryService == null
                    ? ScheduledTaskRunRecoveryService.ResumePlan.none() : recoveryService.plan(run);
            if (resume.waitsForSource()) {
                runService.transitionSystem(run, "STARTING", "WAITING_EXECUTOR", actorId);
                return;
            }
            if (resume.reusable()) {
                recoveryService.reconcile(run);
                run = runDao.findById(workspaceId, runId);
                if (run == null || !"STARTING".equals(run.getStatus())) throw invalid("Run recovery metadata was lost");
            }
            DispatchDO dispatch = resume.reusable()
                    ? dispatchService.enqueueScheduledResume(workspaceId, runId, stepId, run.getInitialAgentId(), 1,
                            resume.sourceDispatch().getId(), resume.degraded(), actorId)
                    : dispatchService.enqueueSubject(workspaceId,
                            ExecutionSourceType.SCHEDULED_TASK_RUN, runId, stepId,
                            run.getInitialAgentId(), 1, actorId);
            dispatchService.pinScheduledAgentVersion(dispatch.getId(), workspaceId, frozenVersion);
            if (!runService.transitionSystem(run, "STARTING", "WAITING_EXECUTOR", actorId)) {
                throw invalid("Run waiting-executor transition was lost");
            }
            dispatchService.runPending(dispatch.getId());
        } catch (BizException failure) {
            if (ErrorCode.SCHEDULED_TASK_INVALID_STATE.getCode().equals(failure.getCode())) {
                fail(run, actorId, failure.getCode() + ": " + failure.getMessage());
                return;
            }
            throw failure;
        } catch (RuntimeException failure) {
            fail(run, actorId, ErrorCode.SCHEDULED_TASK_INVALID_STATE.getCode() + ": " + failure.getMessage());
        }
    }

    /**
     * An after-commit callback still has the completed transaction's JDBC resource bound.
     * Starting in a new physical transaction prevents the delivery path from participating in
     * that completed transaction (and from being rolled back during its cleanup).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void startAfterCommit(long workspaceId, long runId, long actorId) {
        start(workspaceId, runId, actorId);
    }

    private void fail(ScheduledTaskRunDO run, long actorId, String error) {
        if (run != null && run.getId() != null && run.getWorkspaceId() != null) {
            ScheduledTaskRunDO current = runDao.findById(run.getWorkspaceId(), run.getId());
            if (current != null && current.getVersion() != null && current.getStatus() != null) {
                runService.finish(current, "FAILED", null, error, actorId);
            }
        }
    }

    private boolean ownerActive(ScheduledTaskRunDO run) {
        if (userDao == null || workspaceMemberDao == null) return true;
        UserDO user = userDao.findById(run.getOwnerId());
        if (user == null || user.getStatus() == null || user.getStatus() != 0) return false;
        WorkspaceMemberDO member = workspaceMemberDao.findByWorkspaceAndUser(run.getWorkspaceId(), run.getOwnerId());
        return member != null && member.getStatus() != null && member.getStatus() == 0;
    }
    private void ownerInactive(ScheduledTaskRunDO run, long actorId) {
        fail(run, actorId, "OWNER_INACTIVE");
        run.setStatus("FAILED");
        if (notificationService != null) notificationService.status(run, "OWNER_INACTIVE");
        if (taskDao != null) {
            ScheduledTaskDO task = taskDao.findById(run.getWorkspaceId(), run.getScheduledTaskId());
            if (task != null && task.getVersion() != null) taskDao.updateStatus(task.getWorkspaceId(), task.getId(),
                    task.getStatus(), ScheduledTaskStatus.PAUSED.name(), task.getVersion(), actorId);
        }
        if (auditLogService != null) { AuditLogRecord record = new AuditLogRecord();
            record.setTenantId(run.getWorkspaceId()); record.setModule("SCHEDULED_TASK");
            record.setAction("OWNER_INACTIVE"); record.setTargetType("scheduled_task_run"); record.setTargetId(run.getId());
            auditLogService.record(record); }
        if (notifyService != null && run.getOwnerId() != null) { NotifyEvent e=new NotifyEvent(); e.setTenantId(run.getWorkspaceId()); e.setType("SCHEDULED_TASK_OWNER_INACTIVE"); e.setTitle("定时任务已暂停"); e.setContent("任务所有者不可用"); e.setRefType("SCHEDULED_TASK_RUN"); e.setRefId(run.getId()); e.setRecipientIds(List.of(run.getOwnerId())); notifyService.notify(e); }
    }

    private JSONObject parseAndValidate(ScheduledTaskRunDO run) {
        final JSONObject snapshot;
        try { snapshot = JSON.parseObject(run.getExecutionSnapshotJson()); }
        catch (RuntimeException badJson) { throw invalid("execution snapshot is invalid"); }
        if (snapshot == null || !SNAPSHOT_SCHEMA.equals(snapshot.getString("schemaVersion"))) {
            throw invalid("execution snapshot schema is invalid");
        }
        JSONObject task = snapshot.getJSONObject("task");
        JSONObject assignment = snapshot.getJSONObject("assignment");
        JSONObject policies = snapshot.getJSONObject("policies");
        if (task == null || task.getLongValue("id") != positive(run.getScheduledTaskId())
                || blank(task.getString("name")) || blank(task.getString("instructionMd"))
                || assignment == null || assignment.getLongValue("squadId") != positive(run.getSquadId())
                || assignment.getLongValue("initialAgentId") != positive(run.getInitialAgentId())
                || policies == null || blank(policies.getString("sessionMode"))
                || !(snapshot.get("requirementDocuments") instanceof JSONArray)
                || !(snapshot.get("agentContexts") instanceof JSONArray)) {
            throw invalid("execution snapshot is incomplete");
        }
        frozenVersion(snapshot, run.getInitialAgentId());
        validateFrozenDocuments(run, snapshot.getJSONArray("requirementDocuments"));
        return snapshot;
    }

    private void validateFrozenDocuments(ScheduledTaskRunDO run, JSONArray documents) {
        if (artifactDao == null || storage == null) return;
        for (int i = 0; i < documents.size(); i++) {
            JSONObject frozen = documents.getJSONObject(i);
            if (frozen == null || frozen.getLongValue("artifactId") <= 0
                    || blank(frozen.getString("name")) || blank(frozen.getString("ossRef"))
                    || blank(frozen.getString("sha256"))) throw invalid("frozen requirement document is invalid");
            ArtifactDO artifact = artifactDao.findBySourceAndId(run.getWorkspaceId(),
                    ExecutionSourceType.SCHEDULED_TASK.name(), run.getScheduledTaskId(), frozen.getLong("artifactId"));
            if (artifact == null || !Objects.equals(artifact.getTenantId(), run.getWorkspaceId())
                    || !ExecutionSourceType.SCHEDULED_TASK.name().equals(artifact.getSourceType())
                    || !Objects.equals(artifact.getWorkitemId(), run.getScheduledTaskId())
                    || !RequirementDocumentService.TYPE.equals(artifact.getType())
                    || !frozen.getString("name").equals(artifact.getName())
                    || !frozen.getString("ossRef").equals(artifact.getOssRef())) {
                throw invalid("frozen requirement document no longer matches its artifact");
            }
            byte[] content = storage.get(artifact.getOssRef());
            if (content == null || !frozen.getString("sha256").equals(sha256(content))) {
                throw invalid("frozen requirement document content changed");
            }
        }
    }

    private String sha256(byte[] content) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception e) { throw invalid("cannot verify frozen requirement document"); }
    }

    private long frozenVersion(JSONObject snapshot, Long agentId) {
        if (agentId == null || agentId <= 0) throw invalid("initial agent is invalid");
        JSONArray contexts = snapshot.getJSONArray("agentContexts");
        JSONObject matched = null;
        for (int i = 0; contexts != null && i < contexts.size(); i++) {
            JSONObject context = contexts.getJSONObject(i);
            if (context != null && context.getLongValue("agentId") == agentId) {
                if (matched != null) throw invalid("agent context is duplicated");
                matched = context;
            }
        }
        if (matched == null || matched.getLongValue("agentVersionId") <= 0) {
            throw invalid("frozen agent context is missing");
        }
        return matched.getLongValue("agentVersionId");
    }

    private static long positive(Long value) {
        if (value == null || value <= 0) throw invalid("run identity is invalid");
        return value;
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static BizException invalid(String detail) {
        return new BizException(ErrorCode.SCHEDULED_TASK_INVALID_STATE, detail);
    }

    /** Keeps an explicit runtime handoff inside the same immutable Run execution subject. */
    @Transactional(rollbackFor = Exception.class)
    public HandoffResult handoff(DispatchDO source, long targetAgentId) {
        if (source == null || source.executionSourceType() != ExecutionSourceType.SCHEDULED_TASK_RUN
                || source.getTenantId() == null || source.getWorkitemId() == null || targetAgentId <= 0) {
            return HandoffResult.rejected("DISPATCH_NOT_FOUND", "source dispatch is not a scheduled run");
        }
        ScheduledTaskRunDO run = runDao.findById(source.getTenantId(), source.getWorkitemId());
        if (run == null || run.getVersion() == null || run.getStatus() == null) {
            return HandoffResult.rejected("RUN_NOT_FOUND", "scheduled run not found");
        }
        try {
            DispatchDO replay = dispatchService.findHandoffBySource(source.getTenantId(), source.getId());
            if (replay != null) {
                if ("PENDING".equals(replay.getStatus())) dispatchService.runPending(replay.getId());
                return HandoffResult.agent(replay.getAgentId(), replay.getId());
            }
            if (!"SUCCEEDED".equals(source.getStatus()) || !Objects.equals(run.getCurrentAgentId(), source.getAgentId())
                    || !Objects.equals(run.getCurrentStepId(), source.getSdlcStepId())) {
                return HandoffResult.rejected("SOURCE_NOT_CURRENT", "scheduled handoff source is not the completed current assignment");
            }
            JSONObject snapshot = parseAndValidate(run);
            long frozenVersion = frozenVersion(snapshot, targetAgentId);
            JSONObject targetSdlc = frozenSdlc(snapshot, run.getInitialAgentId(), targetAgentId);
            Long stepId = firstFrozenStep(targetSdlc);
            Long sdlcId = targetSdlc == null ? null : targetSdlc.getLong("id");
            if (run.getSdlcId() != null && stepId == null) {
                throw invalid("frozen target agent SDLC is missing");
            }
            if (runDao.updateCurrentAssignment(run.getWorkspaceId(), run.getId(), sdlcId, targetAgentId, stepId,
                    run.getVersion(), 0L) != 1) {
                return HandoffResult.rejected("RUN_VERSION_CONFLICT", "scheduled run changed during handoff");
            }
            int attempt = source.getAttempt() == null ? 2 : source.getAttempt() + 1;
            DispatchDO downstream = dispatchService.enqueueScheduledHandoff(run.getWorkspaceId(), run.getId(), stepId,
                    targetAgentId, source.getId(), attempt, 0L);
            dispatchService.pinScheduledAgentVersion(downstream.getId(), run.getWorkspaceId(), frozenVersion);
            dispatchService.runPending(downstream.getId());
            if (notificationService != null) notificationService.handoff(run);
            return HandoffResult.agent(targetAgentId, downstream.getId());
        } catch (BizException invalid) {
            fail(run, 0L, invalid.getCode() + ": " + invalid.getMessage());
            return HandoffResult.rejected("SCHEDULED_TASK_INVALID_STATE", invalid.getMessage());
        }
    }

    /** Resolve only against the immutable Run ledger, never mutable online-agent state. */
    public HandoffResult handoff(DispatchDO source, String target) {
        if (source == null || target == null || target.isBlank()) return HandoffResult.rejected("TARGET_UNRESOLVED", "scheduled handoff target is required");
        ScheduledTaskRunDO run = runDao.findById(source.getTenantId(), source.getWorkitemId());
        if (run == null) return HandoffResult.rejected("RUN_NOT_FOUND", "scheduled run not found");
        try {
            JSONArray contexts = parseAndValidate(run).getJSONArray("agentContexts");
            for (int i=0;i<contexts.size();i++) { JSONObject c=contexts.getJSONObject(i); JSONObject identity=c==null?null:c.getJSONObject("identity");
                if (c != null && (target.equals(String.valueOf(c.getLongValue("agentId")))
                        || target.equalsIgnoreCase(identity == null ? null : identity.getString("name"))
                        || target.equalsIgnoreCase(identity == null ? null : identity.getString("roleCode")))) {
                    return handoff(source, c.getLongValue("agentId"));
                }
            }
            return HandoffResult.rejected("TARGET_UNRESOLVED", "target is not in the frozen scheduled squad");
        } catch (BizException invalid) { return HandoffResult.rejected("SCHEDULED_TASK_INVALID_STATE", invalid.getMessage()); }
    }

    /** Resume a user-paused source through a fresh, snapshot-pinned continuation. */
    @Transactional(rollbackFor = Exception.class)
    public boolean resumePaused(long workspaceId, long runId, long userId) {
        ScheduledTaskRunDO run = runDao.findById(workspaceId, runId);
        if (run == null || !"QUEUED".equals(run.getStatus()) || run.getVersion() == null) return false;
        List<DispatchDO> rows = dispatchService.listBySource(workspaceId, ExecutionSourceType.SCHEDULED_TASK_RUN, runId);
        DispatchDO source = rows.stream().filter(d -> "PAUSED".equals(d.getStatus()))
                .max(java.util.Comparator.comparing(DispatchDO::getId)).orElse(null);
        if (source == null || source.getAgentId() == null) return false;
        JSONObject snapshot = parseAndValidate(run);
        long version = frozenVersion(snapshot, source.getAgentId());
        if (runDao.initializeExecution(workspaceId, runId, "QUEUED", run.getSdlcId(), source.getAgentId(),
                source.getSdlcStepId(), run.getVersion(), userId) != 1) return false;
        run = runDao.findById(workspaceId, runId);
        if (run == null || !"STARTING".equals(run.getStatus())) return false;
        runService.observePersistedTransition(run, null);
        int attempt = source.getAttempt() == null ? 2 : source.getAttempt() + 1;
        DispatchDO continuation = dispatchService.enqueueScheduledResume(workspaceId, runId, source.getSdlcStepId(),
                source.getAgentId(), attempt, source.getId(), false, userId);
        dispatchService.pinScheduledAgentVersion(continuation.getId(), workspaceId, version);
        if (!runService.transitionSystem(run, "STARTING", "WAITING_EXECUTOR", userId)) return false;
        dispatchService.runPending(continuation.getId());
        return true;
    }

    /** Applies frozen SDLC sequencing after a terminal dispatch; Workitems keep their existing driver. */
    @Transactional(rollbackFor = Exception.class)
    public void onDispatchResult(DispatchDO dispatch, boolean success, String summary, String error) {
        if (dispatch == null || dispatch.executionSourceType() != ExecutionSourceType.SCHEDULED_TASK_RUN) return;
        ScheduledTaskRunDO run = runDao.findById(dispatch.getTenantId(), dispatch.getWorkitemId());
        if (run == null || run.getVersion() == null || !success) {
            if (run != null) fail(run, 0L, error);
            return;
        }
        runService.finish(run, "SUCCEEDED", summary, null, 0L);
    }

    private JSONObject nextFrozenStep(JSONObject sdlc, Long currentStepId) {
        if (currentStepId == null) return null;
        if (sdlc == null || !(sdlc.get("steps") instanceof JSONArray)) return null;
        JSONArray steps = sdlc.getJSONArray("steps");
        for (int i = 0; i < steps.size(); i++) {
            JSONObject step = steps.getJSONObject(i);
            if (step != null && step.getLongValue("id") == currentStepId) {
                return i + 1 < steps.size() ? steps.getJSONObject(i + 1) : null;
            }
        }
        throw invalid("current frozen SDLC step is missing");
    }

    private JSONObject frozenSdlc(JSONObject snapshot, Long initialAgentId, long agentId) {
        JSONArray contexts = snapshot.getJSONArray("agentContexts");
        for (int i = 0; contexts != null && i < contexts.size(); i++) { JSONObject c=contexts.getJSONObject(i);
            if (c != null && c.getLongValue("agentId") == agentId) {
                JSONObject sdlc = c.getJSONObject("sdlc");
                if (sdlc != null) return sdlc;
                break;
            } }
        if (Objects.equals(initialAgentId, agentId)) return snapshot.getJSONObject("sdlc");
        throw invalid("frozen target agent context is missing");
    }
    private Long firstFrozenStep(JSONObject sdlc) {
        if (sdlc == null) return null;
        long stepId = sdlc.getLongValue("currentStepId");
        if (stepId <= 0 || sdlc.getLongValue("id") <= 0) throw invalid("frozen target SDLC entry step is invalid");
        return stepId;
    }
}
