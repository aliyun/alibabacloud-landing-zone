package com.aliyun.autowonder.scheduledtask;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.artifact.RequirementDocumentService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.dispatch.subject.ExecutionSubject;
import com.aliyun.autowonder.dispatch.subject.ExecutionSubjectProvider;
import com.aliyun.autowonder.dispatch.subject.ExecutionSubjectRef;
import com.aliyun.autowonder.taskpackage.PackageContext;
import com.aliyun.autowonder.taskpackage.TaskArtifactRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/** Builds Runtime-compatible packages exclusively from a Scheduled Run's frozen snapshot. */
@Component
public class ScheduledRunExecutionSubjectProvider implements ExecutionSubjectProvider {
    static final String SNAPSHOT_SCHEMA = "autowonder.scheduledTaskExecutionSnapshot.v1";

    private final ScheduledTaskRunDao runDao;
    private final ArtifactDao artifactDao;

    public ScheduledRunExecutionSubjectProvider(ScheduledTaskRunDao runDao, ArtifactDao artifactDao) {
        this.runDao = runDao;
        this.artifactDao = artifactDao;
    }

    @Override
    public ExecutionSourceType type() {
        return ExecutionSourceType.SCHEDULED_TASK_RUN;
    }

    @Override
    public ExecutionSubject load(long workspaceId, long sourceId) {
        ScheduledTaskRunDO run = runDao.findById(workspaceId, sourceId);
        if (run == null || run.getWorkspaceId() == null || run.getWorkspaceId() != workspaceId
                || run.getId() == null || run.getId() != sourceId) {
            throw invalid("scheduled task run not found");
        }
        JSONObject snapshot = parseSnapshot(run.getExecutionSnapshotJson());
        validateSnapshot(run, snapshot);
        return new ScheduledRunSubject(new ExecutionSubjectRef(type(), sourceId), run, snapshot);
    }

    @Override
    public PackageContext assemble(DispatchDO dispatch, ExecutionSubject subject, AgentVersionDO selectedVersion) {
        if (!(subject instanceof ScheduledRunSubject scheduled)
                || !scheduled.ref().equals(new ExecutionSubjectRef(type(), dispatch.getWorkitemId()))) {
            throw new IllegalArgumentException("scheduled run execution subject mismatch");
        }
        ScheduledTaskRunDO run = scheduled.run();
        JSONObject snapshot = scheduled.snapshot();
        if (!Objects.equals(dispatch.getTenantId(), run.getWorkspaceId())
                || dispatch.executionSourceType() != type()
                || !Objects.equals(dispatch.getWorkitemId(), run.getId())) {
            throw invalid("dispatch does not match scheduled run");
        }
        JSONObject assignment = requiredObject(snapshot, "assignment");
        boolean interaction = "CANONICAL_INTERACTION".equals(dispatch.getResumeMode())
                || "SIDE_INTERACTION".equals(dispatch.getResumeMode());
        long currentAgentId = interaction
                ? requirePositive(dispatch.getAgentId(), "dispatch.agentId")
                : requirePositive(run.getCurrentAgentId(), "run.currentAgentId");
        JSONObject agentContext = requireAgentContext(snapshot, currentAgentId);
        long frozenVersionId = requiredPositiveLong(agentContext, "agentVersionId");
        if (selectedVersion == null || selectedVersion.getId() == null
                || selectedVersion.getId() != frozenVersionId
                || dispatch.getAgentVersionId() == null
                || dispatch.getAgentVersionId() != frozenVersionId
                || selectedVersion.getTenantId() == null
                || !Objects.equals(selectedVersion.getTenantId(), run.getWorkspaceId())
                || selectedVersion.getAgentId() == null
                || selectedVersion.getAgentId() != currentAgentId
                || dispatch.getAgentId() == null || dispatch.getAgentId() != currentAgentId) {
            throw invalid("selected agent version does not match frozen assignment");
        }

        JSONObject task = requiredObject(snapshot, "task");
        PackageContext context = new PackageContext();
        context.setTenantId(run.getWorkspaceId());
        context.setDispatchId(dispatch.getId());
        context.setSourceDispatchId(dispatch.getDeliverySourceDispatchId() != null
                ? dispatch.getDeliverySourceDispatchId() : dispatch.getResumeFromDispatchId());
        context.setWorkitemId(run.getId());
        context.setAgentId(currentAgentId);
        context.setAgentVersionId(frozenVersionId);
        context.setExecutorId(dispatch.getExecutorId());
        context.setAttempt(dispatch.getAttempt());
        context.setIdempotencyKey(dispatch.getIdempotencyKey());
        context.setWorkitemTitle(requiredString(task, "name"));
        context.setWorkitemContentMd(requiredString(task, "instructionMd"));
        context.setWorkType("TASK");

        JSONObject identity = requiredObject(agentContext, "identity");
        context.setIdentity(copyMap(identity));
        context.setRoleCode(requiredString(identity, "roleCode"));
        context.setRoleName(requiredString(identity, "name"));
        context.setRepos(copyObjectList(requiredArray(agentContext, "repos"), "repos"));
        context.setRepoMap(optionalMap(agentContext.getJSONObject("repoMap")));
        context.setSkills(copyObjectList(requiredArray(agentContext, "skills"), "skills"));
        context.setMemory(copyStringMap(requiredObject(agentContext, "memory")));
        context.setRoster(copyMap(requiredObject(agentContext, "roster")));
        context.setRequirementDocuments(requirementDocuments(run, snapshot));

        if (!interaction && !Objects.equals(dispatch.getSdlcStepId(), run.getCurrentStepId())) {
            throw invalid("dispatch step does not match scheduled run assignment");
        }
        if (interaction) {
            // A mention is an explicit conversational turn. It may target any
            // frozen participant and intentionally carries no workflow step.
            context.setSdlcId(null); context.setSdlcStepId(null); context.setSdlc(null);
            context.setOmitSdlcFileWhenAbsent(true);
        } else {
            applyFrozenSdlc(context, run, frozenSdlcForCurrentAgent(snapshot, run, agentContext));
        }

        // Comments, guidance, predecessor output and checkpoints become source-aware in Task 10.
        // They must remain empty here instead of accidentally querying same-valued Workitem IDs.
        context.setCommentsMd(null);
        context.setInteractionContextMd(null);
        context.setTeammates(new ArrayList<>());
        context.setSourceRevisionArtifacts(new ArrayList<>());
        return context;
    }

    private void validateSnapshot(ScheduledTaskRunDO run, JSONObject snapshot) {
        if (!SNAPSHOT_SCHEMA.equals(requiredString(snapshot, "schemaVersion"))) {
            throw invalid("unsupported scheduled task snapshot schema");
        }
        JSONObject task = requiredObject(snapshot, "task");
        if (requiredPositiveLong(task, "id") != requirePositive(run.getScheduledTaskId(), "run.scheduledTaskId")) {
            throw invalid("snapshot task id mismatch");
        }
        requiredString(task, "name");
        requiredString(task, "instructionMd");

        JSONObject assignment = requiredObject(snapshot, "assignment");
        if (requiredPositiveLong(assignment, "squadId") != requirePositive(run.getSquadId(), "run.squadId")
                || requiredPositiveLong(assignment, "initialAgentId")
                    != requirePositive(run.getInitialAgentId(), "run.initialAgentId")) {
            throw invalid("snapshot assignment mismatch");
        }
        validateAgentContexts(snapshot, run.getInitialAgentId());

        JSONObject policies = requiredObject(snapshot, "policies");
        if (!Objects.equals(requiredString(policies, "sessionMode"), run.getSessionMode())) {
            throw invalid("snapshot session policy mismatch");
        }
        requiredString(policies, "overlapPolicy");
        JSONObject trigger = requiredObject(snapshot, "trigger");
        if (!Objects.equals(requiredString(trigger, "type"), run.getTriggerType())) {
            throw invalid("snapshot trigger type mismatch");
        }
        requiredString(trigger, "scheduledAt");
        if (!snapshot.containsKey("requirementDocuments")
                || !(snapshot.get("requirementDocuments") instanceof JSONArray)) {
            throw invalid("snapshot requirementDocuments is required");
        }
        if (snapshot.containsKey("sdlc") && snapshot.get("sdlc") != null
                && !(snapshot.get("sdlc") instanceof JSONObject)) {
            throw invalid("snapshot sdlc is invalid");
        }
    }

    private void validateAgentContexts(JSONObject snapshot, Long initialAgentId) {
        JSONArray contexts = requiredArray(snapshot, "agentContexts");
        if (contexts.isEmpty()) {
            throw invalid("snapshot agentContexts must not be empty");
        }
        Set<Long> agentIds = new HashSet<>();
        Set<String> assignments = new HashSet<>();
        boolean foundInitial = false;
        for (int i = 0; i < contexts.size(); i++) {
            JSONObject context = contexts.getJSONObject(i);
            if (context == null) {
                throw invalid("snapshot agentContexts is invalid");
            }
            long agentId = requiredPositiveLong(context, "agentId");
            long versionId = requiredPositiveLong(context, "agentVersionId");
            if (!agentIds.add(agentId) || !assignments.add(agentId + ":" + versionId)) {
                throw invalid("snapshot agent context is duplicated for agent " + agentId);
            }
            JSONObject identity = requiredObject(context, "identity");
            requiredString(identity, "name");
            requiredString(identity, "roleCode");
            requiredArray(context, "repos");
            if (!context.containsKey("repoMap")
                    || (context.get("repoMap") != null && !(context.get("repoMap") instanceof JSONObject))) {
                throw invalid("snapshot agent context repoMap is required");
            }
            requiredArray(context, "skills");
            requiredObject(context, "memory");
            requiredObject(context, "roster");
            foundInitial |= Objects.equals(initialAgentId, agentId);
        }
        if (!foundInitial) {
            throw invalid("snapshot initial agent context is missing");
        }
    }

    private JSONObject requireAgentContext(JSONObject snapshot, long agentId) {
        JSONArray contexts = requiredArray(snapshot, "agentContexts");
        JSONObject match = null;
        for (int i = 0; i < contexts.size(); i++) {
            JSONObject candidate = contexts.getJSONObject(i);
            if (candidate != null && candidate.getLongValue("agentId") == agentId) {
                if (match != null) {
                    throw invalid("snapshot agent context is duplicated for agent " + agentId);
                }
                match = candidate;
            }
        }
        if (match == null) {
            throw invalid("snapshot agent context is missing for agent " + agentId);
        }
        return match;
    }

    private List<TaskArtifactRef> requirementDocuments(ScheduledTaskRunDO run, JSONObject snapshot) {
        List<TaskArtifactRef> result = new ArrayList<>();
        JSONArray documents = snapshot.getJSONArray("requirementDocuments");
        for (int i = 0; i < documents.size(); i++) {
            JSONObject frozen = documents.getJSONObject(i);
            if (frozen == null) {
                throw invalid("frozen requirement document is invalid");
            }
            long artifactId = requiredPositiveLong(frozen, "artifactId");
            String name = requiredString(frozen, "name");
            String ossRef = requiredString(frozen, "ossRef");
            String sha256 = requiredSha256(frozen, "sha256");
            ArtifactDO artifact = artifactDao.findBySourceAndId(run.getWorkspaceId(),
                    ExecutionSourceType.SCHEDULED_TASK.name(), run.getScheduledTaskId(), artifactId);
            if (artifact == null || artifact.getTenantId() == null
                    || !Objects.equals(artifact.getTenantId(), run.getWorkspaceId())
                    || !ExecutionSourceType.SCHEDULED_TASK.name().equals(artifact.getSourceType())
                    || artifact.getWorkitemId() == null
                    || !Objects.equals(artifact.getWorkitemId(), run.getScheduledTaskId())
                    || !RequirementDocumentService.TYPE.equals(artifact.getType())
                    || !name.equals(artifact.getName()) || !ossRef.equals(artifact.getOssRef())) {
                throw invalid("frozen requirement document no longer matches artifact " + artifactId);
            }
            TaskArtifactRef ref = new TaskArtifactRef();
            ref.setName(name);
            ref.setOssRef(ossRef);
            ref.setExpectedSha256(sha256);
            result.add(ref);
        }
        return result;
    }

    private void applyFrozenSdlc(PackageContext context, ScheduledTaskRunDO run, JSONObject frozenSdlc) {
        if (frozenSdlc == null) {
            if (run.getSdlcId() != null || run.getCurrentStepId() != null) {
                throw invalid("run SDLC assignment is not present in snapshot");
            }
            context.setSdlcId(null);
            context.setSdlcStepId(null);
            context.setSdlc(null);
            context.setOmitSdlcFileWhenAbsent(true);
            return;
        }
        long sdlcId = requiredPositiveLong(frozenSdlc, "id");
        long currentStepId = requirePositive(run.getCurrentStepId(), "run.currentStepId");
        if (run.getSdlcId() == null || run.getSdlcId() != sdlcId
                || !containsStep(frozenSdlc.getJSONArray("steps"), currentStepId)) {
            throw invalid("run SDLC assignment does not match frozen workflow");
        }
        Map<String, Object> sdlc = copyMap(frozenSdlc);
        sdlc.put("sdlcId", String.valueOf(sdlcId));
        sdlc.put("currentStepId", String.valueOf(currentStepId));
        context.setSdlcId(sdlcId);
        context.setSdlcStepId(currentStepId);
        context.setSdlc(sdlc);
        context.setOmitSdlcFileWhenAbsent(false);
    }

    private JSONObject frozenSdlcForCurrentAgent(JSONObject snapshot, ScheduledTaskRunDO run,
            JSONObject agentContext) {
        JSONObject current = agentContext.getJSONObject("sdlc");
        if (current != null) return current;
        // Old V1 snapshots stored only the initial Agent's top-level SDLC. They remain valid only
        // while the initial Agent owns the Run; a handoff must carry its own frozen SDLC context.
        if (Objects.equals(run.getCurrentAgentId(), run.getInitialAgentId())) return snapshot.getJSONObject("sdlc");
        if (run.getSdlcId() != null || run.getCurrentStepId() != null) {
            throw invalid("current agent frozen SDLC is missing");
        }
        return null;
    }

    private boolean containsStep(JSONArray steps, long stepId) {
        if (steps == null || steps.isEmpty()) {
            return false;
        }
        for (int i = 0; i < steps.size(); i++) {
            JSONObject step = steps.getJSONObject(i);
            if (step != null && String.valueOf(stepId).equals(step.getString("id"))) {
                return true;
            }
        }
        return false;
    }

    private JSONObject parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            throw invalid("scheduled task execution snapshot is missing");
        }
        try {
            JSONObject snapshot = JSON.parseObject(json);
            if (snapshot == null) {
                throw invalid("scheduled task execution snapshot is missing");
            }
            return snapshot;
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_INVALID_STATE,
                    "scheduled task execution snapshot is damaged");
        }
    }

    private static JSONObject requiredObject(JSONObject parent, String key) {
        JSONObject value = parent == null ? null : parent.getJSONObject(key);
        if (value == null) {
            throw invalid("snapshot " + key + " is required");
        }
        return value;
    }

    private static JSONArray requiredArray(JSONObject parent, String key) {
        Object raw = parent == null ? null : parent.get(key);
        if (!(raw instanceof JSONArray array)) {
            throw invalid("snapshot " + key + " is required");
        }
        return array;
    }

    private static String requiredString(JSONObject parent, String key) {
        String value = parent == null ? null : parent.getString(key);
        if (value == null || value.isBlank()) {
            throw invalid("snapshot " + key + " is required");
        }
        return value;
    }

    private static String requiredSha256(JSONObject parent, String key) {
        String value = requiredString(parent, key);
        if (!value.matches("sha256:[0-9a-fA-F]{64}")) {
            throw invalid("snapshot " + key + " is invalid");
        }
        return value.toLowerCase();
    }

    private static long requiredPositiveLong(JSONObject parent, String key) {
        Long value = parent == null ? null : parent.getLong(key);
        return requirePositive(value, "snapshot." + key);
    }

    private static long requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw invalid(name + " must be positive");
        }
        return value;
    }

    private static Map<String, Object> copyMap(JSONObject source) {
        return new LinkedHashMap<>(source);
    }

    private static Map<String, Object> optionalMap(JSONObject source) {
        return source == null ? null : copyMap(source);
    }

    private static List<Map<String, Object>> copyObjectList(JSONArray source, String name) {
        if (source == null) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            JSONObject item = source.getJSONObject(i);
            if (item == null) {
                throw invalid("snapshot " + name + " is invalid");
            }
            result.add(copyMap(item));
        }
        return result;
    }

    private static Map<String, String> copyStringMap(JSONObject source) {
        Map<String, String> result = new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                if (entry.getValue() == null) {
                    throw invalid("snapshot memory is invalid");
                }
                result.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.SCHEDULED_TASK_INVALID_STATE, message);
    }

    private record ScheduledRunSubject(ExecutionSubjectRef ref, ScheduledTaskRunDO run,
            JSONObject snapshot) implements ExecutionSubject { }
}
