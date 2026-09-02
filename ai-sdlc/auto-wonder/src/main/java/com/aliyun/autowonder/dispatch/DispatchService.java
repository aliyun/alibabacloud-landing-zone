package com.aliyun.autowonder.dispatch;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.artifact.ArtifactOwnerRef;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.evolution.EvolutionTelemetryEvidenceLiteService;
import com.aliyun.autowonder.evolution.EvolutionTrialAssignmentLiteService;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.util.MojibakeDetector;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.storage.ObjectStorageException;
import com.aliyun.autowonder.taskpackage.PackageContext;
import com.aliyun.autowonder.taskpackage.TaskPackageResult;
import com.aliyun.autowonder.taskpackage.TaskPackager;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunService;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunOrchestrator;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskNotificationService;
import org.springframework.context.annotation.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class DispatchService {

    private static final int MAX_ERROR_CHARS = 512;
    private static final int MAX_PROGRESS_TEXT_CHARS = 1024;
    private static final int MAX_SESSION_CANDIDATES = 20;

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    private static final long SYSTEM_USER_ID = 0L;
    private static final long LOCK_TTL_MS = 30_000L;
    private static final long CAPACITY_LOCK_TTL_MS = 10_000L;

    private final DispatchDao dispatchDao;
    private final DispatchRuntimeEventDao runtimeEventDao;
    private final WorkitemDao workitemDao;
    private final AgentDao agentDao;
    private final AgentVersionDao agentVersionDao;
    private final ExecutorSelector executorSelector;
    private final PackageContextAssembler assembler;
    private final TaskPackager taskPackager;
    private final DispatchTransport transport;
    private final SdlcDriver sdlcDriver;
    private final RedisManager redisManager;
    private final DispatchCheckpointService checkpointService;
    private final AuditLogService auditLogService;
    private final ExecutorRegistry executorRegistry;
    private final EvolutionTelemetryEvidenceLiteService telemetryEvidenceService;
	private final EvolutionTrialAssignmentLiteService trialAssignmentService;
    private ScheduledTaskRunService scheduledTaskRunService;
    private ScheduledTaskRunOrchestrator scheduledTaskRunOrchestrator;
    private ScheduledTaskNotificationService scheduledTaskNotificationService;

    @Autowired
    public void setScheduledTaskRunService(ScheduledTaskRunService scheduledTaskRunService) {
        this.scheduledTaskRunService = scheduledTaskRunService;
    }
    @Autowired
    public void setScheduledTaskRunOrchestrator(@Lazy ScheduledTaskRunOrchestrator scheduledTaskRunOrchestrator) {
        this.scheduledTaskRunOrchestrator = scheduledTaskRunOrchestrator;
    }
    @Autowired(required = false)
    public void setScheduledTaskNotificationService(ScheduledTaskNotificationService service) { this.scheduledTaskNotificationService = service; }

    private static final Set<String> EXECUTOR_FAILURE_CATEGORIES = Set.of(
            "agent_error.provider_auth_or_access",
            "agent_error.provider_quota_limit",
            "agent_error.provider_capacity_or_rate_limit",
            "agent_error.provider_server_error",
            "agent_error.provider_network",
            "agent_error.missing_config",
            "agent_error.model_not_found_or_unavailable",
            "agent_error.runtime_version_unsupported",
            "agent_error.runtime_missing_executable",
            "runtime_recovery");

    private static final String SESSION_RECOVERY_COUNTER_SCRIPT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """;
    private static final long SESSION_RECOVERY_COUNTER_TTL_SECONDS = 86400L;
    private static final long MAX_SESSION_RECOVERY_FAILOVERS = 1L;

    @Autowired
    public DispatchService(DispatchDao dispatchDao, DispatchRuntimeEventDao runtimeEventDao,
            WorkitemDao workitemDao, AgentDao agentDao,
            AgentVersionDao agentVersionDao, ExecutorSelector executorSelector,
            PackageContextAssembler assembler, TaskPackager taskPackager,
            DispatchTransport transport, SdlcDriver sdlcDriver, RedisManager redisManager,
            DispatchCheckpointService checkpointService, AuditLogService auditLogService,
            ExecutorRegistry executorRegistry,
			EvolutionTelemetryEvidenceLiteService telemetryEvidenceService,
			EvolutionTrialAssignmentLiteService trialAssignmentService) {
        this.dispatchDao = dispatchDao;
        this.runtimeEventDao = runtimeEventDao;
        this.workitemDao = workitemDao;
        this.agentDao = agentDao;
        this.agentVersionDao = agentVersionDao;
        this.executorSelector = executorSelector;
        this.assembler = assembler;
        this.taskPackager = taskPackager;
        this.transport = transport;
        this.sdlcDriver = sdlcDriver;
        this.redisManager = redisManager;
        this.checkpointService = checkpointService;
        this.auditLogService = auditLogService;
        this.executorRegistry = executorRegistry;
        this.telemetryEvidenceService = telemetryEvidenceService;
		this.trialAssignmentService = trialAssignmentService;
    }

    public DispatchService(DispatchDao dispatchDao, DispatchRuntimeEventDao runtimeEventDao,
            WorkitemDao workitemDao, AgentDao agentDao,
            AgentVersionDao agentVersionDao, ExecutorSelector executorSelector,
            PackageContextAssembler assembler, TaskPackager taskPackager,
            DispatchTransport transport, SdlcDriver sdlcDriver, RedisManager redisManager,
            DispatchCheckpointService checkpointService, AuditLogService auditLogService,
            ExecutorRegistry executorRegistry) {
        this(dispatchDao, runtimeEventDao, workitemDao, agentDao, agentVersionDao,
                executorSelector, assembler, taskPackager, transport, sdlcDriver,
				redisManager, checkpointService, auditLogService, executorRegistry, null, null);
    }

    DispatchService(DispatchDao dispatchDao, DispatchRuntimeEventDao runtimeEventDao,
            WorkitemDao workitemDao, AgentDao agentDao,
            AgentVersionDao agentVersionDao, ExecutorSelector executorSelector,
            PackageContextAssembler assembler, TaskPackager taskPackager,
            DispatchTransport transport, SdlcDriver sdlcDriver, RedisManager redisManager,
            DispatchCheckpointService checkpointService, AuditLogService auditLogService) {
        this(dispatchDao, runtimeEventDao, workitemDao, agentDao, agentVersionDao,
                executorSelector, assembler, taskPackager, transport, sdlcDriver,
				redisManager, checkpointService, auditLogService, null, null, null);
    }

    DispatchService(DispatchDao dispatchDao, DispatchRuntimeEventDao runtimeEventDao,
            WorkitemDao workitemDao, AgentDao agentDao,
            AgentVersionDao agentVersionDao, ExecutorSelector executorSelector,
            PackageContextAssembler assembler, TaskPackager taskPackager,
            DispatchTransport transport, SdlcDriver sdlcDriver, RedisManager redisManager,
            DispatchCheckpointService checkpointService) {
        this(dispatchDao, runtimeEventDao, workitemDao, agentDao, agentVersionDao,
                executorSelector, assembler, taskPackager, transport, sdlcDriver,
				redisManager, checkpointService, null, null, null, null);
    }

    public boolean hasDurableCheckpoint(long workspaceId, long dispatchId,
            long checkpointSeq, String checkpointSha256) {
        return checkpointService != null && checkpointService.matchesDurableReceipt(
                workspaceId, dispatchId, checkpointSeq, checkpointSha256);
    }

    public boolean hasResumableSession(long workspaceId, long dispatchId) {
        return checkpointService != null && checkpointService.hasResumableSession(workspaceId, dispatchId);
    }

    DispatchService(DispatchDao dispatchDao, DispatchRuntimeEventDao runtimeEventDao,
            WorkitemDao workitemDao, AgentDao agentDao,
            AgentVersionDao agentVersionDao, ExecutorSelector executorSelector,
            PackageContextAssembler assembler, TaskPackager taskPackager,
            DispatchTransport transport, SdlcDriver sdlcDriver, RedisManager redisManager) {
        this(dispatchDao, runtimeEventDao, workitemDao, agentDao, agentVersionDao,
                executorSelector, assembler, taskPackager, transport, sdlcDriver,
				redisManager, null, null, null, null, null);
    }

    /** Idempotent on (workitemId, sdlcStepId, attempt). Returns existing or a new PENDING row. */
    public DispatchDO enqueue(long workspaceId, long workitemId, long sdlcStepId, long agentId,
            int attempt, long userId) {
        return enqueueSubject(workspaceId, ExecutionSourceType.WORKITEM, workitemId,
                sdlcStepId, agentId, attempt, userId);
    }

    /** Idempotent on the complete execution subject, step (or root), and attempt. */
    public DispatchDO enqueueSubject(long workspaceId, ExecutionSourceType sourceType,
            long sourceId, Long sdlcStepId, long agentId, int attempt, long creatorId) {
        Objects.requireNonNull(sourceType, "sourceType");
        if (sourceType == ExecutionSourceType.SCHEDULED_TASK) {
            throw new IllegalArgumentException("scheduled task definitions are not executable subjects");
        }
        if (sourceType == ExecutionSourceType.WORKITEM && sdlcStepId == null) {
            throw new IllegalArgumentException("workitem dispatch requires an SDLC step");
        }
        String idem = idempotencyKey(sourceType, sourceId, sdlcStepId, attempt);
        DispatchDO existing = dispatchDao.findByIdempotencyKey(workspaceId, idem);
        String alternateWorkitemKey = sourceType == ExecutionSourceType.WORKITEM
                ? ExecutionSourceType.WORKITEM.name() + ":" + idem : null;
        if (existing == null && alternateWorkitemKey != null) {
            existing = dispatchDao.findByIdempotencyKey(workspaceId, alternateWorkitemKey);
        }
        if (existing != null) {
            log.info("dispatch enqueue idempotent hit key={}", idem);
            return existing;
        }
        DispatchDO d = new DispatchDO();
        d.setTenantId(workspaceId);
        d.setSourceType(sourceType.name());
        d.setWorkitemId(sourceId);
        d.setSdlcStepId(sdlcStepId);
        d.setAgentId(agentId);
        d.setStatus(DispatchStatus.PENDING);
        d.setAttempt(attempt);
        d.setIdempotencyKey(idem);
        d.setCreatorId(creatorId);
        d.setModifierId(creatorId);
        d.setVersion(0);
        try {
            dispatchDao.insert(d);
            log.info("dispatch enqueued dispatchId={} workitemId={} stepId={} agentId={} attempt={}",
                    d.getId(), sourceId, sdlcStepId, agentId, attempt);
            return d;
        } catch (DuplicateKeyException race) {
            DispatchDO winner = dispatchDao.findByIdempotencyKey(workspaceId, idem);
            if (winner == null && alternateWorkitemKey != null) {
                winner = dispatchDao.findByIdempotencyKey(workspaceId, alternateWorkitemKey);
            }
            if (winner == null) {
                throw race;
            }
            return winner;
        }
    }

    /**
     * Persists the immutable version selected by a Scheduled Run snapshot before the row can be driven.
     * A scheduled dispatch is never allowed to silently fall back to an Agent's current online version.
     */
    public void pinScheduledAgentVersion(long dispatchId, long workspaceId, long agentVersionId) {
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null || !Objects.equals(dispatch.getTenantId(), workspaceId)
                || dispatch.executionSourceType() != ExecutionSourceType.SCHEDULED_TASK_RUN
                || dispatch.getAgentId() == null || agentVersionId <= 0) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_INVALID_STATE,
                    "scheduled dispatch cannot be pinned to its frozen agent version");
        }
        // A recovery can encounter an already-delivered row after a process crash.
        // That is safe only when its immutable pin is exactly the requested one;
        // accepting a different existing pin would silently execute the wrong Run ledger.
        if (Objects.equals(dispatch.getAgentVersionId(), agentVersionId)) {
            return;
        }
        if (dispatch.getAgentVersionId() != null) {
            throw pinLost(dispatchId);
        }
        int rows = dispatchDao.pinScheduledAgentVersion(dispatchId, workspaceId, dispatch.getAgentId(),
                agentVersionId, SYSTEM_USER_ID);
        if (rows == 1) {
            dispatch.setAgentVersionId(agentVersionId);
            return;
        }
        // The NULL-only PENDING update deliberately loses races with delivery and
        // other schedulers. Re-read to separate an equal winner from a true loss.
        DispatchDO current = dispatchDao.findById(dispatchId);
        if (current != null && Objects.equals(current.getTenantId(), workspaceId)
                && current.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN
                && Objects.equals(current.getAgentVersionId(), agentVersionId)) {
            return;
        }
        throw pinLost(dispatchId);
    }

    private static BizException pinLost(long dispatchId) {
        return new BizException(ErrorCode.SCHEDULED_TASK_INVALID_STATE,
                "scheduled dispatch version pin was lost: dispatchId=" + dispatchId);
    }

    /** Starts an assignment with a fresh attempt while remaining idempotent for the assignment write. */
    public DispatchDO enqueueAssignment(long workspaceId, long workitemId, long sdlcStepId, long agentId,
            int assignmentVersion, long userId) {
        String idem = "assignment:" + workitemId + ":" + sdlcStepId + ":" + agentId
                + ":" + assignmentVersion;
        DispatchDO existing = dispatchDao.findByIdempotencyKey(workspaceId, idem);
        if (existing != null) {
            log.info("assignment enqueue idempotent hit key={}", idem);
            return existing;
        }
        Integer maxAttempt = dispatchDao.findMaxAttempt(workspaceId, workitemId, sdlcStepId);
        int attempt = (maxAttempt == null ? 0 : maxAttempt) + 1;
        DispatchDO d = new DispatchDO();
        d.setTenantId(workspaceId);
        d.setSourceType(ExecutionSourceType.WORKITEM.name());
        d.setWorkitemId(workitemId);
        d.setSdlcStepId(sdlcStepId);
        d.setAgentId(agentId);
        d.setStatus(DispatchStatus.PENDING);
        d.setAttempt(attempt);
        d.setIdempotencyKey(idem);
        DispatchDO previousFormal = latestFormalDispatch(workspaceId, workitemId);
        if (previousFormal != null) {
            d.setDeliverySourceDispatchId(effectiveDeliverySource(previousFormal));
        }
        d.setCreatorId(userId);
        d.setModifierId(userId);
        d.setVersion(0);
        try {
            dispatchDao.insert(d);
            log.info("assignment dispatch enqueued dispatchId={} workitemId={} stepId={} agentId={} attempt={}",
                    d.getId(), workitemId, sdlcStepId, agentId, attempt);
            return d;
        } catch (DuplicateKeyException race) {
            DispatchDO winner = dispatchDao.findByIdempotencyKey(workspaceId, idem);
            if (winner == null) {
                throw race;
            }
            return winner;
        }
    }

    /** Idempotent on the source dispatch and allocates a fresh attempt for a worker handoff. */
    public DispatchDO enqueueHandoff(long workspaceId, long workitemId, long sdlcStepId, long agentId,
            long sourceDispatchId, long userId) {
        requireWorkitemDispatch(workspaceId, workitemId, sourceDispatchId);
        String idem = handoffIdempotencyKey(sourceDispatchId);
        DispatchDO existing = dispatchDao.findByIdempotencyKey(workspaceId, idem);
        if (existing != null) {
            log.info("handoff enqueue idempotent hit key={}", idem);
            return existing;
        }
        Integer maxAttempt = dispatchDao.findMaxAttempt(workspaceId, workitemId, sdlcStepId);
        int attempt = (maxAttempt == null ? 0 : maxAttempt) + 1;
        DispatchDO d = new DispatchDO();
        d.setTenantId(workspaceId);
        d.setSourceType(ExecutionSourceType.WORKITEM.name());
        d.setWorkitemId(workitemId);
        d.setSdlcStepId(sdlcStepId);
        d.setAgentId(agentId);
        d.setStatus(DispatchStatus.PENDING);
        d.setAttempt(attempt);
        d.setIdempotencyKey(idem);
        d.setDeliverySourceDispatchId(sourceDispatchId);
        DispatchDO priorWorkerDispatch = latestResumableWorkerDispatch(workspaceId, workitemId, agentId);
        if (priorWorkerDispatch != null) {
            d.setResumeFromDispatchId(priorWorkerDispatch.getId());
            d.setResumeMode("RETURNING_WORKER");
        }
        d.setCreatorId(userId);
        d.setModifierId(userId);
        d.setVersion(0);
        try {
            dispatchDao.insert(d);
            log.info("handoff dispatch enqueued dispatchId={} sourceDispatchId={} attempt={}",
                    d.getId(), sourceDispatchId, attempt);
            return d;
        } catch (DuplicateKeyException race) {
            DispatchDO winner = dispatchDao.findByIdempotencyKey(workspaceId, idem);
            if (winner == null) {
                throw race;
            }
            return winner;
        }
    }

    private DispatchDO latestResumableWorkerDispatch(long workspaceId, long workitemId, long agentId) {
        if (checkpointService == null) {
            return null;
        }
        List<DispatchDO> candidates = dispatchDao.listLatestByWorkitemAndAgent(
                workspaceId, workitemId, agentId, MAX_SESSION_CANDIDATES);
        if (candidates == null) {
            return null;
        }
        for (DispatchDO candidate : candidates) {
            if (candidate != null && candidate.getId() != null
                    && checkpointService.hasResumableSession(workspaceId, candidate.getId())) {
                return candidate;
            }
        }
        return null;
    }

    public DispatchDO findHandoffBySource(long workspaceId, long sourceDispatchId) {
        return dispatchDao.findByIdempotencyKey(workspaceId, handoffIdempotencyKey(sourceDispatchId));
    }

    /** Durable source-keyed handoff for a Scheduled Run. */
    public DispatchDO enqueueScheduledHandoff(long workspaceId, long runId, Long stepId, long agentId,
            long sourceDispatchId, int attempt, long userId) {
        String idem = handoffIdempotencyKey(sourceDispatchId);
        DispatchDO existing = dispatchDao.findByIdempotencyKey(workspaceId, idem);
        if (existing != null) return existing;
        DispatchDO d = new DispatchDO();
        d.setTenantId(workspaceId); d.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        d.setWorkitemId(runId); d.setSdlcStepId(stepId); d.setAgentId(agentId); d.setStatus(DispatchStatus.PENDING);
        d.setAttempt(attempt); d.setIdempotencyKey(idem); d.setDeliverySourceDispatchId(sourceDispatchId);
        d.setCreatorId(userId); d.setModifierId(userId); d.setVersion(0);
        try { dispatchDao.insert(d); return d; }
        catch (DuplicateKeyException race) { DispatchDO winner = dispatchDao.findByIdempotencyKey(workspaceId, idem); if (winner == null) throw race; return winner; }
    }

    /**
     * Builds a new immutable scheduled-run Dispatch from a previous Run's
     * checkpoint lineage. CONTINUOUS preserves native-session affinity; the
     * degraded form deliberately carries checkpoints only.
     */
    public DispatchDO enqueueScheduledResume(long workspaceId, long runId, Long stepId, long agentId,
            int attempt, long sourceDispatchId, boolean degraded, long userId) {
        // A degraded checkpoint recovery replaces a fenced native-session row;
        // it must never idempotently return that canceled row.
        String idem = "scheduled-resume:" + runId + ":" + sourceDispatchId
                + (degraded ? ":degraded" : ":native");
        DispatchDO existing = dispatchDao.findByIdempotencyKey(workspaceId, idem);
        if (existing != null) return existing;
        DispatchDO d = new DispatchDO();
        d.setTenantId(workspaceId); d.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        d.setWorkitemId(runId); d.setSdlcStepId(stepId); d.setAgentId(agentId);
        d.setStatus(DispatchStatus.PENDING); d.setAttempt(attempt); d.setIdempotencyKey(idem);
        d.setResumeFromDispatchId(sourceDispatchId);
        d.setResumeMode(degraded ? "DEGRADED_CONTINUOUS" : "CONTINUOUS");
        d.setCreatorId(userId); d.setModifierId(userId); d.setVersion(0);
        try { dispatchDao.insert(d); return d; }
        catch (DuplicateKeyException race) {
            DispatchDO winner = dispatchDao.findByIdempotencyKey(workspaceId, idem);
            if (winner == null) throw race;
            return winner;
        }
    }

    public List<DispatchDO> listBySource(long workspaceId, ExecutionSourceType sourceType, long sourceId) {
        return dispatchDao.listBySource(workspaceId, sourceType.name(), sourceId);
    }

    /** Visible recovery seam: compensation records a source-executor timeout before fallback. */
    public void degradeResume(long runId, long sourceDispatchId, String reason, long workspaceId) {
        log.info("scheduled resume degraded runId={} sourceDispatchId={} reason={} workspaceId={}",
                runId, sourceDispatchId, reason, workspaceId);
    }

    /** Fence an undelivered native-session resume before a checkpoint-only fallback is created. */
    public boolean fencePendingContinuousResume(long workspaceId, long runId) {
        List<DispatchDO> rows = dispatchDao.listBySource(workspaceId,
                ExecutionSourceType.SCHEDULED_TASK_RUN.name(), runId);
        if (rows == null) return true;
        for (DispatchDO row : rows) {
            if (row == null || !"CONTINUOUS".equals(row.getResumeMode())) continue;
            if (DispatchStatus.CANCELED.equals(row.getStatus())) continue;
            if (!DispatchStatus.PENDING.equals(row.getStatus())
                    || !transition(row, DispatchStatus.CANCELED, null, null, null, null,
                    "SOURCE_EXECUTOR_TIMEOUT")) {
                // A concurrent delivery may have won the CAS. Re-read it before
                // allowing a replacement; concurrent native and degraded Runs are unsafe.
                DispatchDO current = row.getId() == null ? null : dispatchDao.findById(row.getId());
                if (current == null || !DispatchStatus.CANCELED.equals(current.getStatus())) return false;
            }
        }
        return true;
    }

    /**
     * Returns whether the next automatic handoff would exceed the allowed number of
     * repetitions for the same directed worker edge. A user-triggered COMMENT_REWORK
     * starts a new delivery segment and resets the counter.
     */
    public boolean hasReachedAutomaticHandoffLimit(long workspaceId, long workitemId,
            long sourceDispatchId, long targetAgentId, int maxRepeats) {
        if (maxRepeats <= 0) {
            return true;
        }
        List<DispatchDO> rows = dispatchDao.listByWorkitem(workspaceId, workitemId);
        if (rows == null || rows.isEmpty()) {
            return false;
        }

        Map<Long, DispatchDO> byId = new HashMap<>();
        long resetBoundary = Long.MIN_VALUE;
        for (DispatchDO row : rows) {
            if (row == null || row.getId() == null) {
                continue;
            }
            byId.put(row.getId(), row);
            if (row.getId() <= sourceDispatchId && "COMMENT_REWORK".equals(row.getResumeMode())) {
                resetBoundary = Math.max(resetBoundary, row.getId());
            }
        }
        DispatchDO source = byId.get(sourceDispatchId);
        if (source == null || source.getAgentId() == null) {
            return false;
        }

        int repeats = 0;
        for (DispatchDO row : rows) {
            if (row == null || row.getId() == null
                    || row.getId() <= resetBoundary || row.getId() > sourceDispatchId
                    || row.getAgentId() == null || row.getAgentId() != targetAgentId) {
                continue;
            }
            Long parentId = handoffSourceId(row.getIdempotencyKey());
            DispatchDO parent = parentId == null ? null : byId.get(parentId);
            if (parent != null && Objects.equals(parent.getAgentId(), source.getAgentId())) {
                repeats++;
            }
        }
        return repeats >= maxRepeats;
    }

    private Long handoffSourceId(String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.startsWith("handoff:")) {
            return null;
        }
        try {
            return Long.parseLong(idempotencyKey.substring("handoff:".length()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Starts one comment turn, either as an isolated active-turn fork or on the worker's canonical session. */
    public DispatchDO enqueueCommentInteraction(long workspaceId, long workitemId, long agentId,
            Long sourceDispatchId, boolean forkSourceSession, Long sdlcStepId,
            long guidanceId, long userId) {
        DispatchDO validatedSource = sourceDispatchId == null ? null
                : requireWorkitemDispatch(workspaceId, workitemId, sourceDispatchId);
        String idem = "guidance:" + guidanceId;
        DispatchDO existing = dispatchDao.findByIdempotencyKey(workspaceId, idem);
        if (existing != null) {
            return existing;
        }
        Integer maxAttempt = dispatchDao.findMaxAttempt(workspaceId, workitemId, sdlcStepId);
        DispatchDO interaction = new DispatchDO();
        interaction.setTenantId(workspaceId);
        interaction.setSourceType(ExecutionSourceType.WORKITEM.name());
        interaction.setWorkitemId(workitemId);
        interaction.setSdlcStepId(sdlcStepId);
        interaction.setAgentId(agentId);
        interaction.setStatus(DispatchStatus.PENDING);
        interaction.setAttempt((maxAttempt == null ? 0 : maxAttempt) + 1);
        interaction.setIdempotencyKey(idem);
        interaction.setResumeFromDispatchId(sourceDispatchId);
        DispatchDO deliverySource = validatedSource == null
                ? latestFormalDispatch(workspaceId, workitemId)
                : validatedSource;
        if (deliverySource != null) {
            interaction.setDeliverySourceDispatchId(effectiveDeliverySource(deliverySource));
        }
        interaction.setResumeMode(forkSourceSession ? "SIDE_INTERACTION" : "CANONICAL_INTERACTION");
        interaction.setCreatorId(userId);
        interaction.setModifierId(userId);
        interaction.setVersion(0);
        try {
            dispatchDao.insert(interaction);
        } catch (DuplicateKeyException race) {
            DispatchDO winner = dispatchDao.findByIdempotencyKey(workspaceId, idem);
            if (winner == null) {
                throw race;
            }
            return winner;
        }
        return interaction;
    }

    /** Source-safe counterpart for a scheduled Run; Runtime wire remains unchanged. */
    public DispatchDO enqueueScheduledRunCommentInteraction(long workspaceId, long runId, long agentId,
            Long sourceDispatchId, long guidanceId, long userId) {
        String idem = "scheduled-run-guidance:" + guidanceId;
        DispatchDO existing = dispatchDao.findByIdempotencyKey(workspaceId, idem);
        if (existing != null) return existing;
        Integer maxAttempt = dispatchDao.findMaxAttemptBySource(workspaceId,
                ExecutionSourceType.SCHEDULED_TASK_RUN.name(), runId, null);
        DispatchDO interaction = new DispatchDO();
        interaction.setTenantId(workspaceId);
        interaction.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        interaction.setWorkitemId(runId);
        interaction.setAgentId(agentId);
        interaction.setStatus(DispatchStatus.PENDING);
        interaction.setAttempt((maxAttempt == null ? 0 : maxAttempt) + 1);
        interaction.setIdempotencyKey(idem);
        interaction.setResumeFromDispatchId(sourceDispatchId);
        interaction.setResumeMode("CANONICAL_INTERACTION");
        interaction.setCreatorId(userId);
        interaction.setModifierId(userId);
        interaction.setVersion(0);
        try {
            dispatchDao.insert(interaction);
        } catch (DuplicateKeyException race) {
            DispatchDO winner = dispatchDao.findByIdempotencyKey(workspaceId, idem);
            if (winner == null) throw race;
            return winner;
        }
        return interaction;
    }

    /** Queue a comment-triggered rework. It remains fenced until the active main dispatch is durably paused. */
    public DispatchDO enqueueInteractionRework(long workspaceId, long workitemId, long agentId,
            long sdlcStepId, Long resumeFromDispatchId, long sourceInteractionDispatchId,
            Long waitForDispatchId, long userId) {
        DispatchDO resumeSource = resumeFromDispatchId == null ? null
                : requireWorkitemDispatch(workspaceId, workitemId, resumeFromDispatchId);
        requireWorkitemDispatch(workspaceId, workitemId, sourceInteractionDispatchId);
        if (waitForDispatchId != null) {
            requireWorkitemDispatch(workspaceId, workitemId, waitForDispatchId);
        }
        String idem = "interaction-rework:" + sourceInteractionDispatchId;
        DispatchDO existing = dispatchDao.findByIdempotencyKey(workspaceId, idem);
        if (existing != null) {
            return existing;
        }
        Integer maxAttempt = dispatchDao.findMaxAttempt(workspaceId, workitemId, sdlcStepId);
        DispatchDO rework = new DispatchDO();
        rework.setTenantId(workspaceId);
        rework.setSourceType(ExecutionSourceType.WORKITEM.name());
        rework.setWorkitemId(workitemId);
        rework.setSdlcStepId(sdlcStepId);
        rework.setAgentId(agentId);
        // The workflow coordinator first binds the authoritative workitem owner/SDLC/step,
        // then releases this row and starts it after that transaction commits.
        rework.setStatus(DispatchStatus.WAITING_FOR_PAUSE);
        rework.setAttempt((maxAttempt == null ? 0 : maxAttempt) + 1);
        rework.setIdempotencyKey(idem);
        rework.setResumeFromDispatchId(resumeFromDispatchId);
        DispatchDO deliverySource = resumeSource;
        if (deliverySource != null) {
            rework.setDeliverySourceDispatchId(effectiveDeliverySource(deliverySource));
        }
        rework.setResumeMode("COMMENT_REWORK");
        rework.setResultSummary(waitForDispatchId == null ? null : "waitForDispatchId=" + waitForDispatchId);
        rework.setCreatorId(userId);
        rework.setModifierId(userId);
        rework.setVersion(0);
        try {
            dispatchDao.insert(rework);
        } catch (DuplicateKeyException race) {
            DispatchDO winner = dispatchDao.findByIdempotencyKey(workspaceId, idem);
            if (winner == null) {
                throw race;
            }
            return winner;
        }
        return rework;
    }

    private DispatchDO latestFormalDispatch(long workspaceId, long workitemId) {
        List<DispatchDO> rows = dispatchDao.listByWorkitem(workspaceId, workitemId);
        if (rows == null) {
            return null;
        }
        return rows.stream()
                .filter(Objects::nonNull)
                .filter(row -> !isInteractionDispatch(row))
                .filter(row -> row.getId() != null)
                .filter(row -> DispatchStatus.SUCCEEDED.equals(row.getStatus()))
                .max(Comparator.comparingLong(DispatchDO::getId))
                .orElse(null);
    }

    private long effectiveDeliverySource(DispatchDO source) {
        // Interaction turns never become formal delivery lineage. A successful formal
        // dispatch does: its own artifacts supersede the predecessor it inherited.
        if (isInteractionDispatch(source) && source.getDeliverySourceDispatchId() != null) {
            return source.getDeliverySourceDispatchId();
        }
        if (DispatchStatus.SUCCEEDED.equals(source.getStatus())) {
            return source.getId();
        }
        // In-flight or failed rows are not authoritative deliveries. Preserve the last
        // accepted predecessor when a caller explicitly points at such a row.
        if (source.getDeliverySourceDispatchId() != null) {
            return source.getDeliverySourceDispatchId();
        }
        return source.getId();
    }

    /** Release one fenced comment rework. External dispatch starts only after its caller commits. */
    public boolean releaseInteractionRework(long workspaceId, long dispatchId) {
        DispatchDO candidate = dispatchDao.findById(dispatchId);
        return candidate != null && candidate.getTenantId() == workspaceId
                && candidate.executionSourceType() == ExecutionSourceType.WORKITEM
                && DispatchStatus.WAITING_FOR_PAUSE.equals(candidate.getStatus())
                && dispatchDao.updateStatus(candidate.getId(), workspaceId, DispatchStatus.PENDING,
                null, null, null, null, null, candidate.getVersion(), 0L) == 1;
    }

    /** Supersede a fenced rework that lost to a newer user comment or whose pause request failed. */
    public boolean cancelWaitingInteractionRework(long workspaceId, long dispatchId) {
        DispatchDO candidate = dispatchDao.findById(dispatchId);
        return candidate != null && candidate.getTenantId() == workspaceId
                && candidate.executionSourceType() == ExecutionSourceType.WORKITEM
                && DispatchStatus.WAITING_FOR_PAUSE.equals(candidate.getStatus())
                && dispatchDao.updateStatus(candidate.getId(), workspaceId, DispatchStatus.CANCELED,
                null, null, null, null, DispatchFailureReason.COMMENT_REWORK,
                candidate.getVersion(), 0L) == 1;
    }

    /** Fence a lost pause once the executor heartbeat no longer reports this dispatch. */
    public boolean cancelPauseFailedIfExecutorReleased(long workspaceId, long dispatchId) {
        DispatchDO current = dispatchDao.findById(dispatchId);
        if (current == null || current.getTenantId() != workspaceId
                || current.executionSourceType() != ExecutionSourceType.WORKITEM
                || !DispatchStatus.PAUSE_FAILED.equals(current.getStatus())
                || current.getExecutorId() == null
                || executorRegistry.isDispatchActive(current.getExecutorId(), dispatchId)) {
            return false;
        }
        return transition(current, DispatchStatus.CANCELED, null, null, null, null,
                DispatchFailureReason.COMMENT_REWORK);
    }

    /** Cancel a not-yet-delivered main dispatch before a comment rework replaces it. */
    public boolean cancelUndeliveredForInteraction(long workspaceId, long dispatchId) {
        for (int retry = 0; retry < 3; retry++) {
            DispatchDO current = dispatchDao.findById(dispatchId);
            if (current == null || current.getTenantId() != workspaceId) {
                return false;
            }
            if (current.executionSourceType() != ExecutionSourceType.WORKITEM) {
                return false;
            }
            if (DispatchStatus.isTerminal(current.getStatus()) || DispatchStatus.PAUSED.equals(current.getStatus())) {
                return true;
            }
            if (!DispatchStatus.PENDING.equals(current.getStatus())
                    && !DispatchStatus.PACKAGING.equals(current.getStatus())) {
                return false;
            }
            if (transition(current, DispatchStatus.CANCELED, null, null, null, null,
                    DispatchFailureReason.COMMENT_REWORK)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create a new fenced dispatch from a failed/stale one. The old dispatch ID
     * is never reused, so delayed frames from the old executor cannot complete
     * the recovered run.
     */
    public DispatchDO continueDispatch(long workspaceId, long workitemId, long sourceDispatchId,
            long userId) {
        String lockKey = "dispatch:continue:" + sourceDispatchId;
        String lockOwner = UUID.randomUUID().toString();
        if (!redisManager.tryAcquireLock(lockKey, lockOwner, LOCK_TTL_MS)) {
            throw new BizException(ErrorCode.CONFLICT, "恢复请求正在处理中");
        }
        try {
            DispatchDO source = dispatchDao.findById(sourceDispatchId);
            if (source == null || source.getTenantId() != workspaceId
                    || source.executionSourceType() != ExecutionSourceType.WORKITEM
                    || source.getWorkitemId() != workitemId) {
                throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
            }
            DispatchDO latestForWorker = dispatchDao.listByWorkitem(workspaceId, workitemId).stream()
                    .filter(item -> java.util.Objects.equals(item.getAgentId(), source.getAgentId()))
                    .reduce((left, right) -> right)
                    .orElse(null);
            if (latestForWorker == null || !latestForWorker.getId().equals(source.getId())) {
                throw new BizException(ErrorCode.CONFLICT, "只能继续该 Worker 的最新一次执行");
            }
            String idem = "continue:" + sourceDispatchId;
            DispatchDO existing = dispatchDao.findByIdempotencyKey(workspaceId, idem);
            if (existing != null) {
                return existing;
            }
            if (!canContinue(source, System.currentTimeMillis())) {
                throw new BizException(ErrorCode.CONFLICT, "当前执行仍在线或已成功，不能继续");
            }
            if (!DispatchStatus.isTerminal(source.getStatus())
                    && !transition(source, DispatchStatus.CANCELED, null, null, null, null,
                            DispatchFailureReason.MANUAL_CONTINUE)) {
                throw new BizException(ErrorCode.CONFLICT, "执行状态已变化，请刷新后重试");
            }
            Integer maxAttempt = dispatchDao.findMaxAttempt(workspaceId, workitemId,
                    source.getSdlcStepId());
            DispatchDO recovery = new DispatchDO();
            recovery.setTenantId(workspaceId);
            recovery.setSourceType(ExecutionSourceType.WORKITEM.name());
            recovery.setWorkitemId(workitemId);
            recovery.setSdlcStepId(source.getSdlcStepId());
            recovery.setAgentId(source.getAgentId());
            recovery.setStatus(DispatchStatus.PENDING);
            recovery.setAttempt((maxAttempt == null ? 0 : maxAttempt) + 1);
            recovery.setIdempotencyKey(idem);
            recovery.setResumeFromDispatchId(sourceDispatchId);
            recovery.setResumeMode("RECOVERY");
            recovery.setCreatorId(userId);
            recovery.setModifierId(userId);
            recovery.setVersion(0);
            try {
                dispatchDao.insert(recovery);
            } catch (DuplicateKeyException race) {
                DispatchDO winner = dispatchDao.findByIdempotencyKey(workspaceId, idem);
                if (winner == null) {
                    throw race;
                }
                return winner;
            }
            runPending(recovery.getId());
            return recovery;
        } finally {
            redisManager.releaseLock(lockKey, lockOwner);
        }
    }

    public static boolean canContinue(DispatchDO dispatch, long nowMillis) {
        if (dispatch == null || DispatchStatus.SUCCEEDED.equals(dispatch.getStatus())) {
            return false;
        }
        if (DispatchStatus.PAUSED.equals(dispatch.getStatus())) {
            return true;
        }
        if (DispatchStatus.PAUSING.equals(dispatch.getStatus())
                || DispatchStatus.PAUSE_FAILED.equals(dispatch.getStatus())) {
            return true;
        }
        if (DispatchStatus.FAILED.equals(dispatch.getStatus())
                || DispatchStatus.TIMEOUT.equals(dispatch.getStatus())
                || DispatchStatus.CANCELED.equals(dispatch.getStatus())) {
            return true;
        }
        return (DispatchStatus.PACKAGING.equals(dispatch.getStatus())
                || DispatchStatus.DISPATCHED.equals(dispatch.getStatus())
                || DispatchStatus.ACKED.equals(dispatch.getStatus())
                || DispatchStatus.RUNNING.equals(dispatch.getStatus()))
                && dispatch.getGmtModified() != null
                && dispatch.getGmtModified().getTime() < nowMillis - 120_000L;
    }

    public DispatchCheckpointDO latestCheckpoint(long workspaceId, long dispatchId) {
        return checkpointService != null ? checkpointService.latest(workspaceId, dispatchId) : null;
    }

    public boolean mayRouteHandoff(long workspaceId, long executorId, long dispatchId) {
        DispatchDO dispatch = loadInboundRow(workspaceId, executorId, dispatchId);
        return dispatch != null && DispatchStatus.SUCCEEDED.equals(dispatch.getStatus())
                && !isInteractionDispatch(dispatch);
    }

    /** A comment-triggered rework is a newer authoritative delivery revision. */
    public boolean isSupersededByInteractionRework(long workspaceId, long workitemId, long sourceDispatchId) {
        return dispatchDao.listByWorkitem(workspaceId, workitemId).stream()
                .filter(Objects::nonNull)
                .anyMatch(row -> row.getId() != null && row.getId() > sourceDispatchId
                        && "COMMENT_REWORK".equals(row.getResumeMode())
                        && !DispatchStatus.CANCELED.equals(row.getStatus())
                        && !DispatchStatus.FAILED.equals(row.getStatus())
                        && !DispatchStatus.TIMEOUT.equals(row.getStatus()));
    }

    /** Package and dispatch a PENDING row. Returns true once capacity was reserved. */
    public boolean runPending(long dispatchId) {
        String lockKey = "dispatch:lock:" + dispatchId;
        String lockOwner = UUID.randomUUID().toString();
        if (!redisManager.tryAcquireLock(lockKey, lockOwner, LOCK_TTL_MS)) {
            return false;
        }
        try {
            log.info("dispatch runPending start dispatchId={}", dispatchId);
            DispatchDO d = dispatchDao.findById(dispatchId);
            if (d == null || !DispatchStatus.PENDING.equals(d.getStatus())) {
                return false;
            }
            long workspaceId = d.getTenantId();

            AgentDO agent = agentDao.findById(d.getAgentId());
            if (agent == null || workspaceId != agent.getTenantId()) {
                log.info("dispatch pending dispatchId={} reason=AGENT_NOT_PUBLISHED", d.getId());
                return false;
            }
            boolean scheduledRun = d.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN;
            Long selectedVersionId = scheduledRun ? d.getAgentVersionId() : agent.getOnlineVersionId();
            if (selectedVersionId == null || selectedVersionId <= 0) {
                if (scheduledRun) {
                    failAndDrive(d, "SCHEDULED_TASK_INVALID_STATE(30005): frozen agent version is missing");
                } else {
                    log.info("dispatch pending dispatchId={} reason=AGENT_NOT_PUBLISHED", d.getId());
                }
                return false;
            }
            AgentVersionDO version = agentVersionDao.findById(selectedVersionId);
            if (version == null || workspaceId != version.getTenantId()
                    || !Objects.equals(version.getAgentId(), d.getAgentId())) {
                if (scheduledRun) {
                    failAndDrive(d, "SCHEDULED_TASK_INVALID_STATE(30005): frozen agent version is invalid");
                }
                log.info("dispatch pending dispatchId={} reason=AGENT_VERSION_NOT_FOUND", d.getId());
                return false;
            }

            String capacityLockKey = "dispatch:agent-capacity:" + d.getAgentId();
            String capacityLockOwner = UUID.randomUUID().toString();
            if (!redisManager.tryAcquireLock(capacityLockKey, capacityLockOwner, CAPACITY_LOCK_TTL_MS)) {
                log.info("dispatch pending dispatchId={} reason=CAPACITY_LOCK_BUSY", d.getId());
                return false;
            }
            Long executorId;
            try {
                Long preferredExecutorId = preferredResumeExecutor(d);
                if ("CONTINUOUS".equals(d.getResumeMode()) && preferredExecutorId != null) {
                    // Do not fall through to a different executor: its local provider session is invalid.
                    executorId = executorSelector.selectStrict(d.getAgentId(), preferredExecutorId);
                } else if (isInteractionDispatch(d)) {
                    executorId = executorSelector.selectForInteraction(d.getAgentId(), preferredExecutorId);
                } else {
                    executorId = preferredExecutorId == null
                            ? executorSelector.select(d.getAgentId())
                            : executorSelector.select(d.getAgentId(), preferredExecutorId);
                }
                if (executorId == null) {
                    log.info("dispatch pending dispatchId={} reason=NO_EXECUTOR_CAPACITY", d.getId());
                    return false;
                }
                if ("SIDE_INTERACTION".equals(d.getResumeMode())
                        && preferredExecutorId != null
                        && !Objects.equals(preferredExecutorId, executorId)) {
                    d.setResumeMode("CANONICAL_INTERACTION");
                    log.info("dispatch fork degraded to canonical dispatchId={} sourceExecutorId={} executorId={}",
                            d.getId(), preferredExecutorId, executorId);
                }
                log.info("dispatch executor selected dispatchId={} executorId={}", d.getId(), executorId);

                if (!transition(d, DispatchStatus.PACKAGING, version.getId(), executorId, null, null, null)) {
                    return false; // lost optimistic race; another worker owns it
                }
            } finally {
                redisManager.releaseLock(capacityLockKey, capacityLockOwner);
            }
            d.setAgentVersionId(version.getId());
            d.setExecutorId(executorId);

            TaskPackageResult pkg;
            try {
                PackageContext ctx = assembler.assemble(d, version);
				if (trialAssignmentService != null) {
					trialAssignmentService.prepare(ctx, d);
				}
                pkg = taskPackager.build(ctx);
            } catch (Exception packagingFailure) {
                ObjectStorageException storageFailure = findCause(packagingFailure, ObjectStorageException.class);
                if (storageFailure != null && storageFailure.isPermanentConfigurationError()) {
                    String reason = "TASK_PACKAGE_STORAGE_CONFIG_ERROR: " + storageFailure.describe();
                    log.error("dispatch packaging permanent failure dispatchId={} reason={}",
                            d.getId(), reason, packagingFailure);
                    failAndDrive(d, reason);
                } else if (isPermanentPackageInputFailure(packagingFailure)) {
                    String reason = "TASK_PACKAGE_CONFIG_ERROR: "
                            + rootFailureMessage(packagingFailure);
                    log.error("dispatch packaging input failure dispatchId={} reason={}",
                            d.getId(), reason, packagingFailure);
                    failAndDrive(d, reason);
                } else {
                    returnPackagingToPending(d.getTenantId(), d.getId());
                }
                throw packagingFailure;
            }

            if (!transition(d, DispatchStatus.DISPATCHED, null, executorId, pkg.getOssRef(), null, null)) {
                return true;
            }
            try {
                transport.dispatch(d, pkg);
            } catch (Exception sendFailure) {
                onBusy(d.getTenantId(), executorId, d.getId());
                throw sendFailure;
            }
            return true;
        } catch (Exception e) {
            log.error("runPending failed dispatchId={}", dispatchId, e);
            return false;
        } finally {
            redisManager.releaseLock(lockKey, lockOwner);
        }
    }

    private Long preferredResumeExecutor(DispatchDO dispatch) {
        if (dispatch == null || dispatch.getResumeFromDispatchId() == null
                || !("RETURNING_WORKER".equals(dispatch.getResumeMode())
                    || "CONTINUOUS".equals(dispatch.getResumeMode())
                    || "SIDE_INTERACTION".equals(dispatch.getResumeMode())
                    || "CANONICAL_INTERACTION".equals(dispatch.getResumeMode()))) {
            return null;
        }
        DispatchDO source = dispatchDao.findById(dispatch.getResumeFromDispatchId());
        if (source == null
                || !Objects.equals(dispatch.getTenantId(), source.getTenantId())
                || dispatch.executionSourceType() != source.executionSourceType()
                || (!"CONTINUOUS".equals(dispatch.getResumeMode())
                    && !Objects.equals(dispatch.getWorkitemId(), source.getWorkitemId()))
                || !Objects.equals(dispatch.getAgentId(), source.getAgentId())) {
            return null;
        }
        return source.getExecutorId();
    }

    private DispatchDO requireWorkitemDispatch(long workspaceId, long workitemId, long dispatchId) {
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null
                || !Long.valueOf(workspaceId).equals(dispatch.getTenantId())
                || dispatch.executionSourceType() != ExecutionSourceType.WORKITEM
                || !Long.valueOf(workitemId).equals(dispatch.getWorkitemId())) {
            throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
        }
        return dispatch;
    }

    void requireWorkitemDispatchBoundary(long workspaceId, long workitemId, long dispatchId) {
        requireWorkitemDispatch(workspaceId, workitemId, dispatchId);
    }

    /** Fill currently available capacity from the durable oldest-first PENDING queue. */
    public void drainPending(long agentId) {
        while (true) {
            java.util.List<DispatchDO> pending = dispatchDao.listOldestPendingByAgent(agentId, 1);
            if (pending == null || pending.isEmpty()) {
                return;
            }
            if (!runPending(pending.get(0).getId())) {
                return;
            }
        }
    }

    // ---- shared helpers (used by Tasks 11/12 as well) ----

    static String idempotencyKey(ExecutionSourceType sourceType, long sourceId,
            Long sdlcStepId, int attempt) {
        String subject = sourceId + ":"
                + (sdlcStepId == null ? "root" : sdlcStepId) + ":" + attempt;
        return sourceType == ExecutionSourceType.WORKITEM
                ? subject : sourceType.name() + ":" + subject;
    }

    static String handoffIdempotencyKey(long sourceDispatchId) {
        return "handoff:" + sourceDispatchId;
    }

    /** Optimistic transition; refreshes local version on success. Returns false on version conflict. */
    boolean transition(DispatchDO d, String status, Long agentVersionId, Long executorId,
            String packageOssRef, String resultSummary, String error) {
        int rows = dispatchDao.updateStatus(d.getId(), d.getTenantId(), status,
                agentVersionId, executorId, packageOssRef, resultSummary,
                truncateCodePoints(error, MAX_ERROR_CHARS),
                d.getVersion(), SYSTEM_USER_ID);
        if (rows == 0) {
            log.info("dispatch transition lost race dispatchId={} targetStatus={}", d.getId(), status);
            return false;
        }
        d.setStatus(status);
        d.setVersion(d.getVersion() + 1);
        log.info("dispatch transition dispatchId={} status={} version={}", d.getId(), status, d.getVersion());
        return true;
    }

    private void failAndDrive(DispatchDO d, String reason) {
        if (!transition(d, DispatchStatus.FAILED, null, null, null, null, reason)) {
            return; // lost optimistic race; the winner drives
        }
        if (d.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN) {
            completeScheduledRun(d, false, null, reason);
        } else {
            sdlcDriver.onFail(d.getTenantId(), d.getWorkitemId(), d.getSdlcStepId());
        }
    }

    private void completeScheduledRun(DispatchDO dispatch, boolean success, String summary, String error) {
        if (scheduledTaskRunService != null) {
            scheduledTaskRunService.completeFromDispatch(dispatch, success, summary, error);
        }
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (type.isInstance(cursor)) {
                return type.cast(cursor);
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    private static boolean isPermanentPackageInputFailure(Throwable failure) {
        BizException businessFailure = findCause(failure, BizException.class);
        if (businessFailure != null
                && ErrorCode.SCHEDULED_TASK_INVALID_STATE.getCode().equals(businessFailure.getCode())) {
            return true;
        }
        if (findCause(failure, IllegalArgumentException.class) != null) {
            return true;
        }
        IllegalStateException illegalState = findCause(failure, IllegalStateException.class);
        if (illegalState == null || illegalState.getMessage() == null) {
            return false;
        }
        return illegalState.getMessage().startsWith("bound capability is missing")
                || illegalState.getMessage().startsWith("capability config must be a JSON object")
                || illegalState.getMessage().startsWith("COMMENT_REWORK_CONTEXT_MISSING:");
    }

    private static String rootFailureMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank()
                ? root.getClass().getSimpleName() : message;
    }

    DispatchDO loadForTenant(long dispatchId) {
        DispatchDO d = dispatchDao.findById(dispatchId);
        if (d == null) {
            throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
        }
        return d;
    }

    /**
     * Resolves the durable execution subject for an inbound runtime artifact.
     * Runtime frames still contain the historical {@code workitemId} field, so
     * that value must never choose the persistence owner for a scheduled Run.
     */
    public ArtifactOwnerRef artifactOwnerForInbound(long workspaceId, long dispatchId) {
        DispatchDO dispatch = loadInboundRow(workspaceId, dispatchId);
        if (dispatch == null || dispatch.getWorkitemId() == null || dispatch.getWorkitemId() <= 0) {
            return null;
        }
        return new ArtifactOwnerRef(dispatch.executionSourceType(), dispatch.getWorkitemId());
    }

    /** DISPATCHED -> ACKED. Idempotent; ignores terminal/foreign-tenant rows. */
    public void onAck(long workspaceId, long dispatchId) {
        log.info("dispatch onAck dispatchId={}", dispatchId);
        DispatchDO d = loadInboundRow(workspaceId, dispatchId);
        if (d == null || DispatchStatus.isTerminal(d.getStatus())) {
            return;
        }
        if (DispatchStatus.DISPATCHED.equals(d.getStatus())) {
            transition(d, DispatchStatus.ACKED, null, null, null, null, null);
        }
    }

    /** ACKED -> RUNNING. Idempotent; no-op once RUNNING. */
    public void onProgress(long workspaceId, long dispatchId) {
        onProgress(workspaceId, dispatchId, null);
    }

    /** ACKED -> RUNNING and persist runtime step/progress signals when provided. */
    public void onProgress(long workspaceId, long dispatchId, JSONObject frame) {
        log.info("dispatch onProgress dispatchId={} workspaceId={} frameKeys={}",
                dispatchId, workspaceId, frame != null ? frame.keySet() : "null");
        DispatchDO d = loadInboundRow(workspaceId, dispatchId);
        if (d == null || DispatchStatus.isTerminal(d.getStatus())) {
            log.info("dispatch onProgress skip dispatchId={} reason={}", dispatchId,
                    d == null ? "NOT_FOUND" : "TERMINAL_" + d.getStatus());
            return;
        }
        recordRuntimeEvent(d, frame);
        if (DispatchStatus.ACKED.equals(d.getStatus()) || DispatchStatus.DISPATCHED.equals(d.getStatus())) {
            if (transition(d, DispatchStatus.RUNNING, null, null, null, null, null)
                    && scheduledTaskRunService != null) {
                scheduledTaskRunService.markRunningFromDispatch(d);
            }
        }
    }

    private void recordRuntimeEvent(DispatchDO d, JSONObject frame) {
        JSONObject detail = extractRuntimeDetail(frame);
        if (detail == null) {
            log.info("dispatch progress skip dispatchId={} agentId={} reason=NULL_DETAIL", d.getId(), d.getAgentId());
            return;
        }
        String eventType = runtimeEventType(detail);
        if (!shouldPersistRuntimeEvent(eventType)) {
            log.info("dispatch progress filtered dispatchId={} agentId={} eventType={} keys={}",
                    d.getId(), d.getAgentId(), eventType, detail.keySet());
            return;
        }
        DispatchRuntimeEventDO event = new DispatchRuntimeEventDO();
        event.setTenantId(d.getTenantId());
        event.setWorkitemId(d.getWorkitemId());
        event.setDispatchId(d.getId());
        event.setAgentId(d.getAgentId());
        event.setEventId(firstText(detail, "eventId"));
        event.setSeq(detail.getLong("seq"));
        event.setEventType(eventType);
        event.setStepId(detail.getLong("stepId"));
        event.setStepKey(firstText(detail, "stepKey", "stepCode", "code", "step"));
        event.setStepOrder(firstInt(detail, "stepOrder", "order"));
        event.setStepName(firstText(detail, "stepName", "name", "title"));
        event.setMessage(truncateCodePoints(
                firstText(detail, "message", "summary", "text", "resultSummary", "log"),
                MAX_PROGRESS_TEXT_CHARS));
        event.setError(truncateCodePoints(firstText(detail, "error", "errorMessage"),
                MAX_PROGRESS_TEXT_CHARS));
        event.setDetailJson(detail.toJSONString());
        if (MojibakeDetector.looksLikeMojibake(event.getMessage())
                || MojibakeDetector.looksLikeMojibake(event.getError())) {
            log.warn("dispatch progress mojibake detected dispatchId={} agentId={} workitemId={} eventType={} "
                            + "executor environment may decode subprocess output with the wrong charset",
                    d.getId(), d.getAgentId(), d.getWorkitemId(), eventType);
        }
        event.setEventTime(detail.getDate("eventTime"));
        runtimeEventDao.insert(event);
        if (scheduledTaskNotificationService != null && d.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN) {
            scheduledTaskNotificationService.runtime(d.getTenantId(), d.getWorkitemId());
        }
        recordAgentAudit(d, "RUNTIME_EVENT", eventType, "runtime.progress",
                new JSONObject()
                        .fluentPut("stepId", event.getStepId())
                        .fluentPut("stepKey", event.getStepKey())
                        .fluentPut("stepOrder", event.getStepOrder())
                        .fluentPut("stepName", event.getStepName())
                        .fluentPut("message", event.getMessage())
                        .fluentPut("error", event.getError()));
        log.info("dispatch progress persisted dispatchId={} agentId={} workitemId={} eventType={} stepOrder={} stepKey={} stepName={}",
                d.getId(), d.getAgentId(), d.getWorkitemId(), eventType,
                event.getStepOrder(), event.getStepKey(), event.getStepName());
    }

    private JSONObject extractRuntimeDetail(JSONObject frame) {
        if (frame == null) {
            return null;
        }
        Object runtimeEvent = frame.get("runtimeEvent");
        if (runtimeEvent instanceof JSONObject json) {
            JSONObject detail = new JSONObject();
            detail.putAll(json);
            return detail;
        }
        Object detailJson = frame.get("detail");
        if (detailJson instanceof JSONObject json) {
            JSONObject detail = new JSONObject();
            detail.putAll(json);
            return detail;
        }
        JSONObject detail = new JSONObject();
        detail.putAll(frame);
        Object log = frame.get("log");
        if (log instanceof String text && text.trim().startsWith("{")) {
            try {
                JSONObject parsed = JSON.parseObject(text);
                if (parsed != null) {
                    detail.putAll(parsed);
                }
            } catch (Exception ignored) {
                // Plain text progress logs are valid, but only structured runtime events drive the UI.
            }
        }
        return detail;
    }

    private boolean shouldPersistRuntimeEvent(String eventType) {
        return isRuntimeEventType(eventType);
    }

    static boolean isRuntimeEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return false;
        }
        return eventType.startsWith("step.")
                || eventType.startsWith("workflow.")
                || eventType.startsWith("agent.")
                || "completion_requested".equals(eventType)
                || eventType.startsWith("package.")
                || eventType.startsWith("workspace.")
                || eventType.startsWith("bootstrap.")
                || eventType.startsWith("repo.")
                || eventType.startsWith("artifact.")
                || eventType.startsWith("upload.")
                || eventType.startsWith("dispatch.")
                || eventType.startsWith("runtime.")
                || eventType.startsWith("session.")
                || eventType.startsWith("turn.")
                || eventType.startsWith("llm.")
                || eventType.startsWith("mcp.")
                || eventType.startsWith("cli.")
                || eventType.startsWith("bash.")
                || eventType.startsWith("skill.")
                || eventType.startsWith("plugin.")
                || eventType.startsWith("subagent.")
                || eventType.startsWith("task.")
                || eventType.startsWith("sdlc.");
    }

    private String runtimeEventType(JSONObject json) {
        String eventType = firstText(json, "eventType", "event", "resultSummary");
        if (shouldPersistRuntimeEvent(eventType)) {
            return eventType;
        }
        String type = firstText(json, "type");
        if (shouldPersistRuntimeEvent(type)) {
            return type;
        }
        String name = firstText(json, "name");
        return shouldPersistRuntimeEvent(name) ? name : eventType;
    }

    private String firstText(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.getString(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Integer firstInt(JSONObject json, String... keys) {
        for (String key : keys) {
            Integer value = json.getInteger(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Terminal result. Idempotent; returns true when the authenticated owner may be ACKed. */
    public boolean onResult(long workspaceId, long dispatchId, boolean success,
            String resultSummary, String error) {
        return onResult(workspaceId, null, dispatchId, success, resultSummary, error);
    }

    public boolean onResult(long workspaceId, Long executorId, long dispatchId, boolean success,
            String resultSummary, String error) {
        return onResult(workspaceId, executorId, dispatchId, success, resultSummary, error, false);
    }

    public boolean onResult(long workspaceId, Long executorId, long dispatchId, boolean success,
            String resultSummary, String error, boolean workflowChanged) {
        return onResult(workspaceId, executorId, dispatchId, success, resultSummary, error,
                workflowChanged, false);
    }

    public boolean onResult(long workspaceId, Long executorId, long dispatchId, boolean success,
            String resultSummary, String error, boolean workflowChanged,
            boolean explicitHandoff) {
        log.info("dispatch onResult dispatchId={} success={}", dispatchId, success);
        DispatchDO d = loadInboundRow(workspaceId, executorId, dispatchId);
        if (d == null) {
            return false;
        }
        if (DispatchStatus.PAUSING.equals(d.getStatus())
                || DispatchStatus.PAUSED.equals(d.getStatus())
                || DispatchStatus.PAUSE_FAILED.equals(d.getStatus())) {
            return false;
        }
        if (DispatchStatus.isTerminal(d.getStatus())) {
            return true;
        }
        String terminal = success ? DispatchStatus.SUCCEEDED : DispatchStatus.FAILED;
        if (!transition(d, terminal, null, null, null, resultSummary, error)) {
            DispatchDO winner = loadInboundRow(workspaceId, executorId, dispatchId);
            return winner != null && DispatchStatus.isTerminal(winner.getStatus());
        }
		if (success && executorId != null && executorRegistry != null) {
			executorRegistry.markProviderAvailable(executorId);
		}
        recordAgentAudit(d, success ? "COMPLETE_DISPATCH" : "FAIL_DISPATCH",
                success ? "dispatch.succeeded" : "dispatch.failed", "runtime.result",
                new JSONObject()
                        .fluentPut("success", success)
                        .fluentPut("resultSummary", resultSummary)
                        .fluentPut("error", error));
        recordEvolutionTelemetryEvidence(d, success, resultSummary, error);
        // Run guidance is a detached conversational turn.  It shares the Run
        // owner solely for packaging/audit; it must not advance or terminalize
        // the formal Run state machine.
        if (d.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN
                && isInteractionDispatch(d)) {
            if (d.getAgentId() != null) drainPending(d.getAgentId());
            return true;
        }
        if (d.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN
                && !(success && explicitHandoff)) {
            if (scheduledTaskRunOrchestrator != null) {
                scheduledTaskRunOrchestrator.onDispatchResult(d, success, resultSummary, error);
            } else {
                completeScheduledRun(d, success, resultSummary, error);
            }
            if (d.getAgentId() != null) {
                drainPending(d.getAgentId());
            }
            return true;
        }
        // Detached comment turns report workflow effects through InteractionWorkflowService.
        // They must never drive the formal SDLC directly, otherwise the stale formal step can
        // hand off while a rework plan is pausing it and routing work back to an earlier worker.
        if (isDetachedInteractionDispatch(d)
                || (isInteractionDispatch(d) && !workflowChanged)) {
            if (d.getAgentId() != null) {
                drainPending(d.getAgentId());
            }
            return true;
        }
        // A valid explicit handoff is the downstream transition. It is mutually
        // exclusive with the ordinary next SDLC step; routing both creates two
        // authoritative successors for one terminal dispatch.
        if (success && explicitHandoff) {
            if (d.getAgentId() != null) {
                drainPending(d.getAgentId());
            }
            return true;
        }
        DriveResult next = success
                ? sdlcDriver.onSuccess(workspaceId, d.getWorkitemId(), d.getSdlcStepId())
                : sdlcDriver.onFail(workspaceId, d.getWorkitemId(), d.getSdlcStepId());
        act(d, next);
        if (d.getAgentId() != null) {
            drainPending(d.getAgentId());
        }
        return true;
    }

    private void recordEvolutionTelemetryEvidence(DispatchDO d, boolean success,
                                                  String resultSummary, String error) {
        if (telemetryEvidenceService == null) {
            return;
        }
        try {
            telemetryEvidenceService.ingestDispatch(d.getId(), success, resultSummary, error,
                    d.getTenantId(), SYSTEM_USER_ID);
        } catch (RuntimeException e) {
            log.warn("evolution telemetry evidence ingestion failed dispatchId={} agentId={} reason={}",
                    d.getId(), d.getAgentId(), e.getMessage());
        }
    }

    public static boolean isExecutorFailureCategory(String failureCategory) {
        return failureCategory != null && EXECUTOR_FAILURE_CATEGORIES.contains(failureCategory);
    }

    /**
     * Treat a provider/account failure as executor infrastructure failover, not as an SDLC failure.
     * The same dispatch and step attempt are returned to PENDING so business retry budgets are untouched.
     */
    public boolean onExecutorUnavailableResult(long workspaceId, long executorId, long dispatchId,
            String failureCategory, String error) {
        if (!isExecutorFailureCategory(failureCategory) || executorRegistry == null) {
            return false;
        }
        DispatchDO current = loadInboundRow(workspaceId, executorId, dispatchId);
        if (current == null) {
            return false;
        }
        executorRegistry.markProviderUnavailable(executorId, failureCategory);
        if ("runtime_recovery".equals(failureCategory)
                && incrementSessionRecoveryFailures(dispatchId) > MAX_SESSION_RECOVERY_FAILOVERS) {
            String reason = "SESSION_RECOVERY_EXHAUSTED: " + error;
            log.error("provider session recovery exhausted dispatchId={} executorId={}",
                    dispatchId, executorId);
            failAndDrive(current, reason);
            return true;
        }
        for (int retry = 0; retry < 3; retry++) {
            if (DispatchStatus.isTerminal(current.getStatus())) {
                return true;
            }
            if (!Long.valueOf(executorId).equals(current.getExecutorId())) {
                return true;
            }
            if (DispatchStatus.PAUSING.equals(current.getStatus())
                    || DispatchStatus.PAUSED.equals(current.getStatus())
                    || DispatchStatus.PAUSE_FAILED.equals(current.getStatus())) {
                return false;
            }
            int rows = dispatchDao.returnOwnedActiveToPending(current.getId(), workspaceId, executorId,
                    current.getVersion(), SYSTEM_USER_ID);
            if (rows == 1) {
                recordExecutorFailoverEvent(current, executorId, failureCategory, error);
                log.warn("executor provider unavailable; dispatch requeued dispatchId={} executorId={} category={}",
                        dispatchId, executorId, failureCategory);
                recordAgentAudit(current, "FAILOVER_DISPATCH", "dispatch.executor_failover",
                        "runtime.result", new JSONObject()
                                .fluentPut("executorId", executorId)
                                .fluentPut("failureCategory", failureCategory)
                                .fluentPut("error", error));
                return true;
            }
            current = dispatchDao.findById(dispatchId);
            if (current == null || current.getTenantId() != workspaceId) {
                return false;
            }
        }
        return false;
    }

    private void recordExecutorFailoverEvent(DispatchDO dispatch, long executorId,
            String failureCategory, String error) {
        String detailError = "Runtime " + executorId + " · " + failureCategory
                + (error == null || error.isBlank() ? "" : " · " + error);
        JSONObject detail = new JSONObject()
                .fluentPut("executorId", executorId)
                .fluentPut("failureCategory", failureCategory)
                .fluentPut("failureScope", "EXECUTOR")
                .fluentPut("error", error)
                .fluentPut("retrying", true);
        DispatchRuntimeEventDO event = new DispatchRuntimeEventDO();
        event.setTenantId(dispatch.getTenantId());
        event.setWorkitemId(dispatch.getWorkitemId());
        event.setDispatchId(dispatch.getId());
        event.setAgentId(dispatch.getAgentId());
        event.setEventId("dispatch:" + dispatch.getId() + ":executor-failover:" + dispatch.getVersion());
        event.setEventType("dispatch.executor_failover");
        event.setStepId(dispatch.getSdlcStepId());
        event.setMessage("Runtime " + executorId + " 执行失败，正在切换其他在线 Runtime");
        event.setError(truncateCodePoints(detailError, MAX_PROGRESS_TEXT_CHARS));
        event.setDetailJson(detail.toJSONString());
        event.setEventTime(new java.util.Date());
        runtimeEventDao.insert(event);
        if (scheduledTaskNotificationService != null && dispatch.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN) {
            scheduledTaskNotificationService.runtime(dispatch.getTenantId(), dispatch.getWorkitemId());
        }
    }

    private long incrementSessionRecoveryFailures(long dispatchId) {
        try {
            Object value = redisManager.eval(SESSION_RECOVERY_COUNTER_SCRIPT,
                    java.util.List.of("dispatch:session-recovery:" + dispatchId),
                    java.util.List.of(Long.toString(SESSION_RECOVERY_COUNTER_TTL_SECONDS)));
            return value instanceof Number number ? number.longValue() : MAX_SESSION_RECOVERY_FAILOVERS + 1;
        } catch (Exception e) {
            log.error("session recovery counter unavailable dispatchId={}", dispatchId, e);
            return MAX_SESSION_RECOVERY_FAILOVERS + 1;
        }
    }

    public boolean isInteractionDispatch(DispatchDO dispatch) {
        return dispatch != null && ("COMMENT_INTERACTION".equals(dispatch.getResumeMode())
                || "SIDE_INTERACTION".equals(dispatch.getResumeMode())
                || "CANONICAL_INTERACTION".equals(dispatch.getResumeMode()));
    }

    private boolean isDetachedInteractionDispatch(DispatchDO dispatch) {
        return dispatch != null && ("SIDE_INTERACTION".equals(dispatch.getResumeMode())
                || "CANONICAL_INTERACTION".equals(dispatch.getResumeMode()));
    }

    private static String truncateCodePoints(String value, int maxCodePoints) {
        if (value == null || value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private void recordAgentAudit(DispatchDO d, String action, String eventType,
            String triggerSource, JSONObject detail) {
        if (auditLogService == null || d == null) {
            return;
        }
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(d.getTenantId());
        record.setActorId(d.getAgentId());
        record.setActorType("AGENT");
        record.setModule("DISPATCH");
        record.setAction(action);
        record.setTargetType("dispatch");
        record.setTargetId(d.getId());
        record.setTriggerType("EVENT");
        record.setTriggerSource(triggerSource);
        record.setEventType(eventType);
        record.detail("workitemId", d.getWorkitemId())
                .detail("sdlcStepId", d.getSdlcStepId());
        if (detail != null) {
            detail.forEach(record::detail);
        }
        auditLogService.record(record);
    }

    /** Interpret a DriveResult from the SDLC driver. */
    private void act(DispatchDO current, DriveResult next) {
        log.info("dispatch drive dispatchId={} action={}", current.getId(), next.getKind());
        switch (next.getKind()) {
            case ENQUEUE:
                DispatchDO created = enqueue(current.getTenantId(), current.getWorkitemId(),
                        next.getNextStepId(), next.getNextAgentId(), 1, SYSTEM_USER_ID);
                runPending(created.getId());
                break;
            case RETRY:
                retry(current, next.getMaxAttempts());
                break;
            case STOP:
            default:
                // nothing to do
        }
    }

    private DispatchDO loadInboundRow(long workspaceId, long dispatchId) {
        return loadInboundRow(workspaceId, null, dispatchId);
    }

    private DispatchDO loadInboundRow(long workspaceId, Long executorId, long dispatchId) {
        DispatchDO d = dispatchDao.findById(dispatchId);
        if (d == null || workspaceId != d.getTenantId()
                || (executorId != null && !executorId.equals(d.getExecutorId()))) {
            return null;
        }
        return d;
    }

    public boolean onBusy(long workspaceId, long executorId, long dispatchId) {
        DispatchDO d = loadInboundRow(workspaceId, executorId, dispatchId);
        if (d == null || !DispatchStatus.DISPATCHED.equals(d.getStatus())) {
            return false;
        }
        int rows = dispatchDao.returnDispatchedToPending(d.getId(), workspaceId, executorId,
                d.getVersion(), SYSTEM_USER_ID);
        if (rows == 1) {
            log.info("dispatch returned to pending dispatchId={} executorId={} reason=AT_CAPACITY",
                    dispatchId, executorId);
            return true;
        }
        return false;
    }

    public boolean returnPackagingToPending(long workspaceId, long dispatchId) {
        DispatchDO d = loadInboundRow(workspaceId, dispatchId);
        if (d == null || !DispatchStatus.PACKAGING.equals(d.getStatus())) {
            return false;
        }
        int rows = dispatchDao.returnPackagingToPending(
                d.getId(), workspaceId, d.getVersion(), SYSTEM_USER_ID);
        if (rows == 1) {
            log.info("dispatch packaging returned to pending dispatchId={}", dispatchId);
            return true;
        }
        return false;
    }

    public void renewActiveLeases(long workspaceId, long executorId, java.util.List<Long> dispatchIds) {
        if (executorRegistry != null) {
            executorRegistry.updateRunningDispatches(executorId, dispatchIds);
        }
        if (dispatchIds == null || dispatchIds.isEmpty()) {
            return;
        }
        java.util.List<Long> owned = new java.util.ArrayList<>(
                new java.util.LinkedHashSet<>(dispatchIds));
        if (owned.size() > 50) {
            owned = owned.subList(0, 50);
        }
        dispatchDao.touchOwnedActive(workspaceId, executorId, owned);
    }

    /** Re-enqueue the same (workitem, step) at attempt+1 while budget remains; else hand to human. */
    void retry(DispatchDO failed, int maxAttempts) {
        int nextAttempt = failed.getAttempt() + 1;
        log.info("dispatch retry dispatchId={} attempt={}/{}", failed.getId(), nextAttempt, maxAttempts);
        if (nextAttempt > maxAttempts) {
            // budget exhausted -> human handoff via the fail branch (no retry action returned)
            sdlcDriver.onFail(failed.getTenantId(), failed.getWorkitemId(), failed.getSdlcStepId());
            return;
        }
        DispatchDO retryRow = enqueue(failed.getTenantId(), failed.getWorkitemId(),
                failed.getSdlcStepId(), failed.getAgentId(), nextAttempt, SYSTEM_USER_ID);
        runPending(retryRow.getId());
    }

    /** Executor deadline exceeded: terminate as TIMEOUT and drive the fail branch. */
    public void onTimeout(long workspaceId, long dispatchId) {
        log.info("dispatch onTimeout dispatchId={}", dispatchId);
        DispatchDO d = loadInboundRow(workspaceId, dispatchId);
        if (d == null || DispatchStatus.isTerminal(d.getStatus())) {
            return;
        }
        if (!DispatchStatus.ACKED.equals(d.getStatus())
                && !DispatchStatus.RUNNING.equals(d.getStatus())) {
            log.info("dispatch timeout ignored before executor ack dispatchId={} status={}",
                    dispatchId, d.getStatus());
            return;
        }
        if (!transition(d, DispatchStatus.TIMEOUT, null, null, null, null, DispatchFailureReason.TIMEOUT)) {
            return;
        }
        sdlcDriver.onFail(workspaceId, d.getWorkitemId(), d.getSdlcStepId());
        if (d.getAgentId() != null) {
            drainPending(d.getAgentId());
        }
    }
}
