package com.aliyun.autowonder.scheduledtask;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.agent.AgentRepoPermDao;
import com.aliyun.autowonder.agent.AgentRepoPermDO;
import com.aliyun.autowonder.agent.AgentSkillDao;
import com.aliyun.autowonder.agent.AgentSkillDO;
import com.aliyun.autowonder.agent.AgentMemoryRefDao;
import com.aliyun.autowonder.agent.AgentMemoryRefDO;
import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.artifact.RequirementDocumentService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.squad.SquadMemberDO;
import com.aliyun.autowonder.squad.SquadMemberDao;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.repo.RepoDao;
import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoRelationDao;
import com.aliyun.autowonder.repo.RepoRelationDO;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.skill.SkillDO;
import com.aliyun.autowonder.memory.MemoryDao;
import com.aliyun.autowonder.memory.MemoryDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.dispatch.PackageContextAssembler;
import com.aliyun.autowonder.scheduledtask.compat.RequiresScheduledTaskCapability;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Creates immutable, idempotent scheduled-task run rows. Starting a queued Run is Task 8's concern. */
@Service
@RequiresScheduledTaskCapability(entry = "scheduler")
public class ScheduledTaskTriggerService {
    static final String SNAPSHOT_SCHEMA = "autowonder.scheduledTaskExecutionSnapshot.v1";
    private final ScheduledTaskRunDao runDao;
    private final ArtifactDao artifactDao;
    private final SquadMemberDao squadMemberDao;
    private final AgentDao agentDao;
    private final AgentVersionDao agentVersionDao;
    private final ObjectStorage storage;
    private AgentRepoPermDao repoPermDao;
    private RepoDao repoDao;
    private RepoRelationDao repoRelationDao;
    private AgentSkillDao skillDao;
    private SkillDao skillCatalogDao;
    private AgentMemoryRefDao memoryRefDao;
    private MemoryDao memoryDao;
    private SdlcStepDao sdlcStepDao;
    private final Clock clock;
    private ScheduledTaskDao taskDao;
    private ScheduledTaskRunOrchestrator runOrchestrator;
    private ScheduledTaskMetrics metrics;
    private ScheduledTaskNotificationService notificationService;

    @Autowired
    public ScheduledTaskTriggerService(ScheduledTaskRunDao runDao, ArtifactDao artifactDao,
            SquadMemberDao squadMemberDao, AgentDao agentDao, AgentVersionDao agentVersionDao,
            ObjectStorage storage) {
        this(runDao, artifactDao, squadMemberDao, agentDao, agentVersionDao, storage, Clock.systemUTC());
    }

    @Autowired
    public void setSnapshotDependencies(AgentRepoPermDao repoPermDao, RepoDao repoDao,
            RepoRelationDao repoRelationDao, AgentSkillDao skillDao, SkillDao skillCatalogDao,
            AgentMemoryRefDao memoryRefDao, MemoryDao memoryDao) {
        this.repoPermDao = repoPermDao; this.repoDao = repoDao; this.repoRelationDao = repoRelationDao;
        this.skillDao = skillDao; this.skillCatalogDao = skillCatalogDao;
        this.memoryRefDao = memoryRefDao; this.memoryDao = memoryDao;
    }
    @Autowired
    public void setSdlcStepDao(SdlcStepDao sdlcStepDao) { this.sdlcStepDao = sdlcStepDao; }

    ScheduledTaskTriggerService(ScheduledTaskRunDao runDao, ArtifactDao artifactDao,
            SquadMemberDao squadMemberDao, AgentDao agentDao, AgentVersionDao agentVersionDao,
            ObjectStorage storage, Clock clock) {
        this.runDao = runDao; this.artifactDao = artifactDao; this.squadMemberDao = squadMemberDao;
        this.agentDao = agentDao; this.agentVersionDao = agentVersionDao; this.storage = storage; this.clock = clock;
    }

    /** Workspace-scoped manual entry point used by the controller. */
    @RequiresScheduledTaskCapability(entry = "scheduler")
    @Transactional(rollbackFor = Exception.class)
    public ScheduledTaskRunDO fireManual(long workspaceId, long taskId, String requestId) {
        if (taskDao == null) throw new IllegalStateException("ScheduledTaskDao is required for manual triggering");
        ScheduledTaskDO task = taskDao.findById(workspaceId, taskId);
        if (task == null || !Objects.equals(task.getWorkspaceId(), workspaceId)) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_NOT_FOUND);
        }
        return fireManual(task, requestId);
    }

    @Autowired
    public void setTaskDao(ScheduledTaskDao taskDao) { this.taskDao = taskDao; }

    @Autowired
    public void setRunOrchestrator(ScheduledTaskRunOrchestrator runOrchestrator) {
        this.runOrchestrator = runOrchestrator;
    }
    @Autowired(required = false)
    public void setObservability(ScheduledTaskMetrics metrics, ScheduledTaskNotificationService notificationService) {
        this.metrics = metrics; this.notificationService = notificationService;
    }

    public static String scheduledKey(long taskId, Instant scheduledAt) {
        return "task:" + taskId + ":scheduled:" + scheduledAt;
    }
    public static String manualKey(long taskId, String requestId) {
        if (requestId == null || requestId.isBlank()) throw new BizException(ErrorCode.SCHEDULED_TASK_VALIDATION_FAILED, "requestId is required");
        return "task:" + taskId + ":manual:" + requestId.trim();
    }

    @RequiresScheduledTaskCapability(entry = "scheduler")
    @Transactional(rollbackFor = Exception.class)
    public ScheduledTaskRunDO fireScheduled(ScheduledTaskDO task, Instant scheduledAt, Instant now) {
        return create(task, scheduledAt, "SCHEDULED", scheduledKey(task.getId(), scheduledAt), now, null, false);
    }

    @RequiresScheduledTaskCapability(entry = "scheduler")
    @Transactional(rollbackFor = Exception.class)
    public ScheduledTaskRunDO fireManual(ScheduledTaskDO task, String requestId) {
        Instant now = clock.instant();
        return create(task, now, "MANUAL", manualKey(task.getId(), requestId), now, null, false);
    }

    /** Retained for expiry callers: a true skip means the start deadline has elapsed. */
    @RequiresScheduledTaskCapability(entry = "scheduler")
    @Transactional(rollbackFor = Exception.class)
    public ScheduledTaskRunDO fireMisfire(ScheduledTaskDO task, Instant scheduledAt, Instant now, boolean skip) {
        return fireMisfire(task, scheduledAt, now, skip ? "START_DEADLINE" : null, false);
    }

    @RequiresScheduledTaskCapability(entry = "scheduler")
    @Transactional(rollbackFor = Exception.class)
    public ScheduledTaskRunDO fireMisfire(ScheduledTaskDO task, Instant scheduledAt, Instant now,
            String skipReason) {
        return fireMisfire(task, scheduledAt, now, skipReason, false);
    }

    @RequiresScheduledTaskCapability(entry = "scheduler")
    @Transactional(rollbackFor = Exception.class)
    public ScheduledTaskRunDO fireMisfire(ScheduledTaskDO task, Instant scheduledAt, Instant now,
            String skipReason, boolean bypassOverlap) {
        if (skipReason != null && !"MISFIRE_POLICY".equals(skipReason) && !"START_DEADLINE".equals(skipReason)) {
            throw new IllegalArgumentException("unsupported misfire skip reason: " + skipReason);
        }
        return create(task, scheduledAt, "MISFIRE", scheduledKey(task.getId(), scheduledAt), now,
                skipReason, bypassOverlap);
    }

    private ScheduledTaskRunDO create(ScheduledTaskDO task, Instant scheduledAt, String triggerType,
            String triggerKey, Instant now, String forcedSkip, boolean bypassOverlap) {
        ScheduledTaskDO decided = lockForOverlapDecision(task, triggerType, forcedSkip, bypassOverlap);
        requireRunnable(decided, !"MANUAL".equals(triggerType));
        String skipReason = forcedSkip;
        if (!bypassOverlap && skipReason == null && hasActive(decided) && ("SKIP".equals(decided.getOverlapPolicy())
                || "CONTINUOUS".equals(decided.getSessionMode()))) skipReason = "OVERLAP";
        ScheduledTaskRunDO run = baseRun(decided, scheduledAt, triggerType, triggerKey, skipReason);
        ScheduledTaskRunDO persisted;
        try {
            runDao.insert(run);
            persisted = run;
        } catch (DuplicateKeyException duplicate) {
            ScheduledTaskRunDO recovered = runDao.findByTriggerKey(task.getWorkspaceId(), triggerKey);
            if (recovered == null) throw duplicate;
            persisted = recovered;
        }
        if (persisted == run && metrics != null) {
            metrics.created(triggerType);
            metrics.status(persisted.getStatus(), persisted.getSkipReason());
        }
        if (persisted == run && notificationService != null) notificationService.status(persisted, triggerType);
        startImmediatelyWhenEligible(decided, persisted);
        return persisted;
    }

    private void startImmediatelyWhenEligible(ScheduledTaskDO task, ScheduledTaskRunDO run) {
        if (runOrchestrator == null || run == null || run.getId() == null
                || !"QUEUED".equals(run.getStatus()) || "QUEUE".equals(task.getOverlapPolicy())) {
            return;
        }
        long workspaceId = run.getWorkspaceId();
        long runId = run.getId();
        long actorId = run.getCreatorId() == null ? 0L : run.getCreatorId();
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runOrchestrator.startAfterCommit(workspaceId, runId, actorId);
                }
            });
            return;
        }
        runOrchestrator.start(workspaceId, runId, actorId);
    }

    /**
     * A task-row lock is acquired before querying active Runs and kept through the Run insert.
     * Scanner claims its cursor first, then takes this same task-row lock; manual firing only takes
     * this lock. No path takes a Run lock before the task lock, so the order is stable.
     */
    private ScheduledTaskDO lockForOverlapDecision(ScheduledTaskDO task, String triggerType,
            String forcedSkip, boolean bypassOverlap) {
        if (bypassOverlap || forcedSkip != null
                || (!"SKIP".equals(task.getOverlapPolicy()) && !"CONTINUOUS".equals(task.getSessionMode()))) {
            return task;
        }
        if (taskDao == null) throw new IllegalStateException("ScheduledTaskDao is required for overlap decisions");
        ScheduledTaskDO locked = taskDao.findByIdForUpdate(task.getWorkspaceId(), task.getId());
        if (locked == null || !Objects.equals(locked.getWorkspaceId(), task.getWorkspaceId())) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_NOT_FOUND);
        }
        requireRunnable(locked, !"MANUAL".equals(triggerType));
        return locked;
    }

    private boolean hasActive(ScheduledTaskDO task) {
        // This follows findByIdForUpdate in the same transaction.  A locking
        // read is intentional: under MySQL REPEATABLE READ it sees the current
        // committed set after waiting on the parent task lock, rather than a
        // stale snapshot established by the initial task lookup.
        List<ScheduledTaskRunDO> active = runDao.findActiveByTaskForUpdate(task.getWorkspaceId(), task.getId());
        return active != null && !active.isEmpty();
    }

    private ScheduledTaskRunDO baseRun(ScheduledTaskDO task, Instant scheduledAt, String triggerType,
            String triggerKey, String skipReason) {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setWorkspaceId(task.getWorkspaceId()); run.setScheduledTaskId(task.getId()); run.setTriggerKey(triggerKey);
        run.setTriggerType(triggerType); run.setScheduledAt(Date.from(scheduledAt));
        run.setStatus(skipReason == null ? ScheduledTaskRunStatus.QUEUED.name() : ScheduledTaskRunStatus.SKIPPED.name());
        run.setSkipReason(skipReason); run.setSquadId(task.getSquadId()); run.setInitialAgentId(task.getInitialAgentId());
        run.setCurrentAgentId(task.getInitialAgentId()); run.setSessionMode(task.getSessionMode());
        run.setOwnerId(task.getCreatorId()); run.setCreatorId(task.getCreatorId()); run.setModifierId(task.getCreatorId());
        JSONObject sdlc = frozenSdlc(task);
        if (sdlc != null) { run.setSdlcId(sdlc.getLong("id")); run.setCurrentStepId(sdlc.getLong("currentStepId")); }
        run.setExecutionSnapshotJson(snapshot(task, scheduledAt, triggerType, sdlc));
        return run;
    }

    private String snapshot(ScheduledTaskDO task, Instant scheduledAt, String triggerType, JSONObject sdlc) {
        JSONObject root = new JSONObject(true); root.put("schemaVersion", SNAPSHOT_SCHEMA);
        root.put("task", map("id", task.getId(), "name", task.getName(), "instructionMd", task.getInstructionMd()));
        root.put("assignment", map("squadId", task.getSquadId(), "initialAgentId", task.getInitialAgentId()));
        root.put("sdlc", sdlc);
        root.put("agentContexts", agentContexts(task));
        root.put("requirementDocuments", requirementDocuments(task));
        root.put("policies", map("sessionMode", task.getSessionMode(), "overlapPolicy", task.getOverlapPolicy(),
                "misfirePolicy", task.getMisfirePolicy(), "startDeadlineSeconds", task.getStartDeadlineSeconds(),
                "affinityTimeoutSeconds", task.getAffinityTimeoutSeconds(), "scheduleType", task.getScheduleType(),
                "timezone", task.getTimezone()));
        root.put("trigger", map("type", triggerType, "scheduledAt", scheduledAt.toString()));
        return JSON.toJSONString(root);
    }

    private JSONArray agentContexts(ScheduledTaskDO task) {
        JSONArray out = new JSONArray(); Set<Long> seen = new HashSet<>(); boolean initialFound = false;
        List<SquadMemberDO> members = squadMemberDao.listBySquad(task.getSquadId());
        if (members != null) for (SquadMemberDO member : members) {
            if (member == null || !Objects.equals(member.getTenantId(), task.getWorkspaceId()) || !seen.add(member.getAgentId())) continue;
            AgentDO agent = agentDao.findById(member.getAgentId());
            if (agent == null || !Objects.equals(agent.getTenantId(), task.getWorkspaceId()) || agent.getOnlineVersionId() == null) continue;
            AgentVersionDO version = agentVersionDao.findById(agent.getOnlineVersionId());
            if (version == null || !Objects.equals(version.getTenantId(), task.getWorkspaceId())) continue;
            out.add(agentContext(agent, version)); initialFound |= Objects.equals(agent.getId(), task.getInitialAgentId());
        }
        if (!initialFound) throw new BizException(ErrorCode.SCHEDULED_TASK_INVALID_STATE, "initial agent is not an executable squad member");
        if (out.isEmpty()) throw new BizException(ErrorCode.SCHEDULED_TASK_INVALID_STATE, "squad has no executable online agents");
        return out;
    }

    private JSONObject agentContext(AgentDO agent, AgentVersionDO version) {
        JSONObject identity;
        try { identity = version.getIdentityJson() == null ? null : JSON.parseObject(version.getIdentityJson()); }
        catch (RuntimeException ignored) { identity = null; }
        if (identity == null) identity = new JSONObject(true);
        identity.putIfAbsent("name", version.getRoleName()); identity.putIfAbsent("roleCode", version.getRoleCode());
        JSONObject result = new JSONObject(true); result.put("agentId", agent.getId()); result.put("agentVersionId", version.getId());
        result.put("identity", identity); JSONArray repos = frozenRepos(version.getTenantId(), version.getId());
        result.put("repos", repos); result.put("repoMap", frozenRepoMap(version.getTenantId(), repos));
        result.put("skills", frozenSkills(version.getTenantId(), version.getId())); result.put("memory", frozenMemory(version.getTenantId(), version.getId()));
        result.put("roster", frozenRoster(agent.getTenantId(), agent.getId()));
        JSONObject sdlc = frozenSdlc(version);
        if (sdlc != null) result.put("sdlc", sdlc);
        return result;
    }

    private JSONArray frozenRepos(Long workspaceId, Long versionId) { JSONArray out = new JSONArray(); if (repoPermDao == null) return out;
        List<AgentRepoPermDO> perms = repoPermDao.listByVersion(versionId); if (perms == null) return out;
        for (AgentRepoPermDO p : perms) { if (p == null || !Objects.equals(workspaceId, p.getTenantId())) continue; RepoDO r = repoDao.findById(p.getRepoId()); if (r == null || !Objects.equals(workspaceId, r.getTenantId())) continue;
            boolean writable = "WRITE".equalsIgnoreCase(p.getPermLevel());
            out.add(map("repoId", r.getId(), "name", r.getName(), "url", r.getUrl(), "ref", r.getDefaultBranch(), "path", r.getName(),
                    "mode", writable ? "eager" : "lazy", "allowCommit", writable, "allowPush", writable, "allowNetwork", true)); }
        return out; }
    private JSONObject frozenRepoMap(Long workspaceId, JSONArray repos) { JSONArray ids = new JSONArray(); JSONArray relations = new JSONArray(); Set<Long> seen = new HashSet<>();
        for (Object raw : repos) { JSONObject repo = (JSONObject) raw; Long id = repo.getLong("repoId"); if (id == null || !seen.add(id)) continue; ids.add(id); if (repoRelationDao == null) continue; List<RepoRelationDO> rows = repoRelationDao.listByRepoId(workspaceId, id); if (rows == null) continue; for (RepoRelationDO rel : rows) { if (rel == null || !Objects.equals(workspaceId, rel.getTenantId())) continue; relations.add(map("id", rel.getId(), "fromRepoId", rel.getFromRepoId(), "toRepoId", rel.getToRepoId(), "relationType", rel.getRelationType(), "description", rel.getDescription())); } }
        return map("boundRepoIds", ids, "relations", relations); }
    private JSONArray frozenSkills(Long workspaceId, Long versionId) { JSONArray out = new JSONArray(); if (skillDao == null) return out;
        for (Map<String, Object> capability : PackageContextAssembler.buildCapabilities(skillDao, skillCatalogDao, workspaceId, versionId)) out.add(new JSONObject(capability)); return out; }
    private JSONObject frozenMemory(Long workspaceId, Long versionId) { JSONObject out = new JSONObject(true); if (memoryRefDao == null) return out; List<AgentMemoryRefDO> refs = memoryRefDao.listByVersion(versionId); if (refs == null) return out; int i=0; for (AgentMemoryRefDO ref : refs) { if (i >= 50 || ref == null || !Objects.equals(workspaceId, ref.getTenantId())) continue; MemoryDO memory = memoryDao.findById(ref.getMemoryId()); if (memory != null && Objects.equals(workspaceId, memory.getTenantId()) && "ADOPTED".equals(memory.getStatus()) && memory.getContentMd() != null) out.put("mem_" + i++, memory.getContentMd()); } return out; }
    private JSONObject frozenRoster(Long workspaceId, Long selfAgentId) { JSONArray digital = new JSONArray(); Set<Long> seen = new HashSet<>(); List<SquadMemberDO> memberships = squadMemberDao.listByAgent(selfAgentId); if (memberships != null) for (SquadMemberDO membership : memberships) { if (membership == null || !Objects.equals(workspaceId, membership.getTenantId())) continue; List<SquadMemberDO> mates = squadMemberDao.listBySquad(membership.getSquadId()); if (mates == null) continue; for (SquadMemberDO mate : mates) { if (mate == null || !Objects.equals(workspaceId, mate.getTenantId()) || Objects.equals(selfAgentId, mate.getAgentId()) || !seen.add(mate.getAgentId())) continue; AgentDO agent = agentDao.findById(mate.getAgentId()); if (agent == null || !Objects.equals(workspaceId, agent.getTenantId()) || agent.getOnlineVersionId() == null) continue; AgentVersionDO version = agentVersionDao.findById(agent.getOnlineVersionId()); if (version != null && Objects.equals(workspaceId, version.getTenantId())) digital.add(map("agentId", agent.getId(), "roleCode", version.getRoleCode(), "roleName", version.getRoleName())); } } return map("digitalTeammates", digital, "humanTeammates", new JSONArray()); }
    private JSONObject frozenSdlc(ScheduledTaskDO task) { AgentDO initial = agentDao.findById(task.getInitialAgentId()); if (initial == null || !Objects.equals(task.getWorkspaceId(), initial.getTenantId()) || initial.getOnlineVersionId() == null) return null; AgentVersionDO version = agentVersionDao.findById(initial.getOnlineVersionId()); return version == null || !Objects.equals(task.getWorkspaceId(), version.getTenantId()) ? null : frozenSdlc(version); }
    private JSONObject frozenSdlc(AgentVersionDO version) { if (sdlcStepDao == null || version == null || version.getSdlcId() == null) return null; List<SdlcStepDO> rows = sdlcStepDao.listBySdlc(version.getSdlcId()); if (rows == null) return null; List<SdlcStepDO> valid = rows.stream().filter(s -> s != null && Objects.equals(version.getTenantId(), s.getTenantId())).sorted(Comparator.comparing(s -> s.getStepOrder() == null ? Integer.MAX_VALUE : s.getStepOrder())).toList(); if (valid.isEmpty()) return null; JSONArray steps = new JSONArray(); for (SdlcStepDO step : valid) { JSONObject item = map("id", String.valueOf(step.getId()), "name", step.getName(), "kind", step.getKind(), "required", step.getRequired() == null || step.getRequired(), "instruction", step.getInstructionMd(), "checklist", jsonArray(step.getChecklistJson()), "gatePolicy", jsonObject(step.getGatePolicyJson())); if (step.getTimeoutSeconds() != null) item.put("timeoutSeconds", step.getTimeoutSeconds()); if (step.getRetryBudget() != null) item.put("retryBudget", step.getRetryBudget()); steps.add(item); } return map("id", version.getSdlcId(), "workflow", "agent-internal-workflow", "currentStepId", valid.get(0).getId(), "steps", steps, "outputContract", map("reviewerHandoffRequired", false)); }
    private static JSONArray jsonArray(String raw) { try { return raw == null || raw.isBlank() ? new JSONArray() : JSON.parseArray(raw); } catch (RuntimeException ignored) { return new JSONArray(); } }
    private static JSONObject jsonObject(String raw) { try { return raw == null || raw.isBlank() ? new JSONObject(true) : JSON.parseObject(raw); } catch (RuntimeException ignored) { return new JSONObject(true); } }

    private JSONArray requirementDocuments(ScheduledTaskDO task) {
        JSONArray docs = new JSONArray();
        List<ArtifactDO> artifacts = artifactDao.listBySource(task.getWorkspaceId(), ExecutionSourceType.SCHEDULED_TASK.name(), task.getId(), RequirementDocumentService.TYPE);
        if (artifacts != null) for (ArtifactDO a : artifacts) {
            if (a == null || !Objects.equals(a.getTenantId(), task.getWorkspaceId())
                    || !ExecutionSourceType.SCHEDULED_TASK.name().equals(a.getSourceType())
                    || !RequirementDocumentService.TYPE.equals(a.getType())
                    || !Objects.equals(a.getWorkitemId(), task.getId()) || a.getOssRef() == null) {
                throw new BizException(ErrorCode.SCHEDULED_TASK_INVALID_STATE,
                        "requirement document does not belong to scheduled task: " + (a == null ? null : a.getId()));
            }
            byte[] bytes = storage.get(a.getOssRef());
            if (bytes == null) throw new BizException(ErrorCode.SCHEDULED_TASK_INVALID_STATE,
                    "requirement document is unavailable: " + a.getId());
            docs.add(map("artifactId", a.getId(), "name", a.getName(), "ossRef", a.getOssRef(), "sha256", sha256(bytes)));
        }
        return docs;
    }
    private static String sha256(byte[] content) { try { return "sha256:" + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static JSONObject map(Object... kv) { JSONObject result = new JSONObject(true); for (int i=0;i<kv.length;i+=2) result.put(String.valueOf(kv[i]), kv[i+1]); return result; }
    private static void requireRunnable(ScheduledTaskDO task, boolean allowExhausted) {
        if (task == null || task.getId() == null || task.getWorkspaceId() == null
                || (!ScheduledTaskStatus.ACTIVE.name().equals(task.getStatus())
                && !(allowExhausted && ScheduledTaskStatus.EXHAUSTED.name().equals(task.getStatus())))) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_INVALID_STATE);
        }
    }
}
