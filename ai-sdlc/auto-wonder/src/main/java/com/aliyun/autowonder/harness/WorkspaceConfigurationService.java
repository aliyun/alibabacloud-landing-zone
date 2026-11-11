package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.agent.AgentService;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.agent.dto.RepoPermRequest;
import com.aliyun.autowonder.agent.dto.SkillRequest;
import com.aliyun.autowonder.agent.dto.UpdateConfigRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.repo.RepoService;
import com.aliyun.autowonder.repo.dto.CreateRelationRequest;
import com.aliyun.autowonder.repo.dto.CreateRepoRequest;
import com.aliyun.autowonder.repo.dto.RepoVO;
import com.aliyun.autowonder.sdlc.SdlcService;
import com.aliyun.autowonder.sdlc.dto.CreateSdlcRequest;
import com.aliyun.autowonder.sdlc.dto.CreateStepRequest;
import com.aliyun.autowonder.sdlc.dto.SdlcVO;
import com.aliyun.autowonder.sdlc.dto.StepVO;
import com.aliyun.autowonder.sdlc.dto.UpdateStepRequest;
import com.aliyun.autowonder.skill.SkillService;
import com.aliyun.autowonder.skill.dto.SkillVO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 分组配置：变量、仓库、依赖边、角色权限、Skill 绑定与 SDLC 指令差异。
 * 只接受精确 changes，拒绝未知变量与任意覆盖；共享对象先隔离副本再改。
 */
@Service
public class WorkspaceConfigurationService {

    public static final String SNAPSHOT_CONFIGURATION = "CONFIGURATION";
    public static final String RUN_CONFIGURATION = "CONFIGURATION";
    private static final Set<String> CHANGE_KEYS = Set.of("variables", "repositories", "relations",
            "repoPermissions", "skillBindings", "sdlcInstructions");

    private final WorkspaceOnboardingDao dao;
    private final WorkspaceOnboardingService onboardingService;
    private final HarnessPreviewService previewService;
    private final HarnessTemplateCatalog catalog;
    private final RepoService repoService;
    private final AgentService agentService;
    private final SdlcService sdlcService;
    private final SkillService skillService;

    public WorkspaceConfigurationService(WorkspaceOnboardingDao dao, WorkspaceOnboardingService onboardingService,
            HarnessPreviewService previewService, HarnessTemplateCatalog catalog, RepoService repoService,
            AgentService agentService, SdlcService sdlcService, SkillService skillService) {
        this.dao = dao;
        this.onboardingService = onboardingService;
        this.previewService = previewService;
        this.catalog = catalog;
        this.repoService = repoService;
        this.agentService = agentService;
        this.sdlcService = sdlcService;
        this.skillService = skillService;
    }

    /** 初始化前只校验冻结的配置意图，数字人 ID 在模板初始化后由原配置入口解析。 */
    public Map<String, Object> normalizeInitializationChanges(long workspaceId, long userId,
            WorkspaceOnboardingDO onboarding, Map<String, Object> changes) {
        onboardingService.require(workspaceId, userId, onboarding.getId());
        Set<String> allowed = Set.of("repositories", "relations", "repoPermissions", "skillBindings");
        if (changes == null || changes.isEmpty() || !allowed.containsAll(changes.keySet())) {
            throw new BizException(ErrorCode.HARNESS_CONFIGURATION_INVALID);
        }
        HarnessTemplate template = onboardingService.selectedTemplate(onboarding);
        JSONObject normalized = new JSONObject();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, Object> known = knownRepos(onboarding);
        normalizeRepositories(changes, normalized, conflicts, known);
        normalizeRelations(changes, normalized, conflicts, known);
        normalizePermissions(changes, template, normalized, conflicts, known, onboarding, false);
        normalizeSkillBindings(changes, template, normalized, conflicts);
        JSONArray repositories = normalized.getJSONArray("repositories");
        if (repositories == null || repositories.isEmpty()) {
            conflicts.add(keyedConflict("REPOSITORY_REQUIRED", "repositories",
                            "harness.initialization.repository_required", "初始化必须配置至少一个仓库"));
        }
        Set<String> repoNames = new LinkedHashSet<>();
        if (repositories != null) {
            for (Object value : repositories) {
                JSONObject repo = (JSONObject) value;
                if (!repoNames.add(repo.getString("name"))) {
                    conflicts.add(keyedConflict("REPOSITORY_DUPLICATE", repo.getString("name"),
                            "harness.initialization.repository_duplicate", "仓库名重复"));
                }
            }
        }
        Set<String> permissionKeys = new LinkedHashSet<>();
        JSONArray permissions = normalized.getJSONArray("repoPermissions");
        if (permissions != null) {
            for (Object value : permissions) {
                JSONObject permission = (JSONObject) value;
                String key = permission.getString("roleCode") + ":" + permission.getString("repo");
                if (!permissionKeys.add(key)) {
                    conflicts.add(keyedConflict("PERMISSION_DUPLICATE", key,
                            "harness.initialization.permission_duplicate", "角色仓库权限重复"));
                }
            }
        }
        for (String repo : repoNames) {
            for (HarnessAgent agent : template.getAgents()) {
                if (catalog.repoPermissionFor(agent.roleCode()) != null
                        && !permissionKeys.contains(agent.roleCode() + ":" + repo)) {
                    conflicts.add(keyedConflict("PERMISSION_REQUIRED", agent.roleCode() + ":" + repo,
                            "harness.initialization.permission_required", "初始化缺少角色仓库权限"));
                }
            }
        }
        JSONArray relations = normalized.getJSONArray("relations");
        Set<String> relationKeys = new LinkedHashSet<>();
        if (relations != null) {
            for (Object value : relations) {
                JSONObject relation = (JSONObject) value;
                String key = relation.getString("from") + "->" + relation.getString("to");
                if (Objects.equals(relation.getString("from"), relation.getString("to"))
                        || !relationKeys.add(key)) {
                    conflicts.add(keyedConflict("RELATION_INVALID", key,
                            "harness.initialization.relation_invalid", "仓库依赖不能自引用或重复"));
                }
            }
        }
        Set<String> bindingKeys = new LinkedHashSet<>();
        JSONArray bindings = normalized.getJSONArray("skillBindings");
        if (bindings != null) {
            for (Object value : bindings) {
                JSONObject binding = (JSONObject) value;
                String role = binding.getString("roleCode");
                String name = binding.getString("skillName");
                if (!bindingKeys.add(role + ":" + name)) {
                    conflicts.add(keyedConflict("SKILL_BINDING_DUPLICATE", role + ":" + name,
                            "harness.initialization.skill_binding_duplicate", "Skill 绑定重复"));
                }
                try {
                    ensureSkillId(name, workspaceId);
                } catch (BizException missing) {
                    conflicts.add(keyedConflict("SKILL_BINDING_MISSING", name,
                            "harness.initialization.skill_binding_missing", "Skill 不在当前空间"));
                }
            }
        }
        return Map.of("changes", normalized, "conflicts", conflicts);
    }

    public Map<String, Object> preview(long workspaceId, long userId, Map<String, Object> args) {
        long onboardingId = required(WorkspaceOnboardingService.longArg(args, "onboardingId"), "onboardingId");
        Integer expectedVersion = WorkspaceOnboardingService.intArg(args, "expectedVersion");
        WorkspaceOnboardingDO onboarding = onboardingService.require(workspaceId, userId, onboardingId);
        if (expectedVersion == null || !expectedVersion.equals(onboarding.getVersion())) {
            throw new BizException(ErrorCode.HARNESS_ONBOARDING_VERSION_CONFLICT);
        }
        requireInitialized(onboarding);
        HarnessTemplate template = onboardingService.selectedTemplate(onboarding);
        Map<String, Object> changes = WorkspaceOnboardingService.mapArg(args, "changes");
        if (changes == null || changes.isEmpty()) {
            throw new BizException(ErrorCode.HARNESS_CONFIGURATION_INVALID);
        }
        for (String key : changes.keySet()) {
            if (!CHANGE_KEYS.contains(key)) {
                throw BizException.keyed(ErrorCode.HARNESS_CONFIGURATION_INVALID, "harness.config.variable_unknown",
                        new Object[]{key}, "配置差异包含不支持的字段");
            }
        }
        Map<String, Object> knownRepos = knownRepos(onboarding);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        JSONObject normalized = new JSONObject();

        normalizeVariables(changes, template, normalized, conflicts);
        normalizeRepositories(changes, normalized, conflicts, knownRepos);
        normalizeRelations(changes, normalized, conflicts, knownRepos);
        normalizePermissions(changes, template, normalized, conflicts, knownRepos, onboarding);
        normalizeSkillBindings(changes, template, normalized, conflicts);
        normalizeSdlcInstructions(changes, template, normalized, conflicts);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("onboardingId", onboarding.getId());
        summary.put("configVersion", onboarding.getConfigVersion());
        summary.put("variableTokens", normalized.getJSONArray("variables") == null ? List.of()
                : normalized.getJSONArray("variables").size());
        summary.put("repositories", size(normalized, "repositories"));
        summary.put("relations", size(normalized, "relations"));
        summary.put("repoPermissions", size(normalized, "repoPermissions"));
        summary.put("skillBindings", size(normalized, "skillBindings"));
        summary.put("sdlcInstructions", size(normalized, "sdlcInstructions"));
        summary.put("changes", normalized);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("conflicts", conflicts);
        if (!conflicts.isEmpty()) {
            result.put("snapshotId", null);
            result.put("snapshotHash", null);
            result.put("expiresAt", null);
            return result;
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("onboardingId", onboarding.getId());
        content.put("onboardingVersion", onboarding.getVersion());
        content.put("templateKey", onboarding.getTemplateKey());
        content.put("templateVersion", onboarding.getTemplateVersion());
        content.put("templateSha256", onboarding.getTemplateSha256());
        content.put("changes", normalized);
        content.put("knownRepositories", knownRepos);
        content.put("objects", onboardingService.draft(onboarding).getJSONObject("objects"));
        String contentJson = JSON.toJSONString(content);

        HarnessSnapshotDO snapshot = new HarnessSnapshotDO();
        snapshot.setTenantId(workspaceId);
        snapshot.setWorkspaceId(workspaceId);
        snapshot.setOnboardingId(onboarding.getId());
        snapshot.setSnapshotType(SNAPSHOT_CONFIGURATION);
        snapshot.setOnboardingVersion(onboarding.getVersion());
        snapshot.setTemplateKey(onboarding.getTemplateKey());
        snapshot.setTemplateVersion(onboarding.getTemplateVersion());
        snapshot.setTemplateSha256(onboarding.getTemplateSha256());
        snapshot.setContentJson(contentJson);
        snapshot.setContentSha256(HarnessPreviewService.sha256(contentJson));
        snapshot.setExpiresAt(Date.from(Instant.now().plus(30, ChronoUnit.MINUTES)));
        snapshot.setCreatorId(userId);
        dao.insertSnapshot(snapshot);

        result.put("snapshotId", String.valueOf(snapshot.getId()));
        result.put("snapshotHash", snapshot.getContentSha256());
        result.put("expiresAt", snapshot.getExpiresAt().toInstant().toString());
        return result;
    }

    public Map<String, Object> apply(long workspaceId, long userId, Map<String, Object> args) {
        long snapshotId = required(WorkspaceOnboardingService.longArg(args, "snapshotId"), "snapshotId");
        HarnessRunDO existing = dao.findRunBySnapshot(workspaceId, snapshotId);
        if (existing != null) {
            onboardingService.require(workspaceId, userId, existing.getOnboardingId());
            return runView(existing);
        }
        HarnessSnapshotDO snapshot = previewService.requireSnapshot(workspaceId, snapshotId,
                WorkspaceOnboardingService.stringArg(args, "snapshotHash"), SNAPSHOT_CONFIGURATION);
        WorkspaceOnboardingDO onboarding = onboardingService.require(workspaceId, userId, snapshot.getOnboardingId());
        previewService.consumeSnapshot(snapshot);
        HarnessRunDO run = new HarnessRunDO();
        run.setTenantId(workspaceId);
        run.setWorkspaceId(workspaceId);
        run.setOnboardingId(onboarding.getId());
        run.setSnapshotId(snapshot.getId());
        run.setRunType(RUN_CONFIGURATION);
        run.setStatus("RUNNING");
        run.setCreatorId(userId);
        dao.insertRun(run);
        onboardingService.advanceStage(onboarding, userId, WorkspaceOnboardingService.STAGE_CONFIGURING);
        return execute(run, onboarding, snapshot, workspaceId, userId);
    }

    public Map<String, Object> resume(long workspaceId, long userId, Map<String, Object> args) {
        long runId = required(WorkspaceOnboardingService.longArg(args, "runId"), "runId");
        HarnessRunDO run = dao.findRun(runId, workspaceId);
        if (run == null || !RUN_CONFIGURATION.equals(run.getRunType())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        WorkspaceOnboardingDO onboarding = onboardingService.require(workspaceId, userId, run.getOnboardingId());
        if ("SUCCEEDED".equals(run.getStatus())) {
            return runView(run);
        }
        HarnessSnapshotDO snapshot = dao.findSnapshot(run.getSnapshotId(), workspaceId);
        if (snapshot == null) {
            throw new BizException(ErrorCode.HARNESS_SNAPSHOT_EXPIRED);
        }
        dao.updateRun(run.getId(), workspaceId, "RUNNING", run.getResultJson(), null);
        run.setStatus("RUNNING");
        return execute(run, onboarding, snapshot, workspaceId, userId);
    }

    public Map<String, Object> get(long workspaceId, long userId, Map<String, Object> args) {
        long runId = required(WorkspaceOnboardingService.longArg(args, "runId"), "runId");
        HarnessRunDO run = dao.findRun(runId, workspaceId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        onboardingService.require(workspaceId, userId, run.getOnboardingId());
        return runView(run);
    }

    private Map<String, Object> execute(HarnessRunDO run, WorkspaceOnboardingDO onboarding,
            HarnessSnapshotDO snapshot, long workspaceId, long userId) {
        JSONObject content = previewService.content(snapshot);
        JSONObject changes = content.getJSONObject("changes");
        JSONObject objects = content.getJSONObject("objects");
        Set<String> reusedSdcls = stringSet(objects, "reusedSdcls");
        Set<String> ownedSdcls = stringSet(objects, "ownedSdcls");
        Map<String, Long> repos = new LinkedHashMap<>();
        JSONObject known = content.getJSONObject("knownRepositories");
        if (known != null) {
            known.forEach((name, id) -> repos.put(name, ((Number) id).longValue()));
        }
        Set<String> appliedKeys = new LinkedHashSet<>();
        try {
            int stepNo = 0;
            JSONArray variables = changes.getJSONArray("variables");
            if (variables != null && !variables.isEmpty()) {
                stepNo++;
                applyVariables(onboarding, variables, objects, reusedSdcls, ownedSdcls, run, stepNo,
                        workspaceId, userId);
            }
            JSONArray repositories = changes.getJSONArray("repositories");
            if (repositories != null) {
                for (Object item : repositories) {
                    JSONObject repo = (JSONObject) item;
                    String name = repo.getString("name");
                    Long repoId = runStep(run, onboarding, ++stepNo, "repo:" + name, "REPO",
                            () -> ensureRepo(repo, workspaceId, userId));
                    repos.put(name, repoId);
                }
            }
            JSONArray relations = changes.getJSONArray("relations");
            if (relations != null) {
                for (Object item : relations) {
                    JSONObject relation = (JSONObject) item;
                    String from = relation.getString("from");
                    String to = relation.getString("to");
                    runStep(run, onboarding, ++stepNo, "relation:" + from + "->" + to, "RELATION",
                            () -> ensureRelation(repos.get(from), repos.get(to), relation, workspaceId, userId));
                }
            }
            JSONArray permissions = changes.getJSONArray("repoPermissions");
            Set<String> touchedRoles = new LinkedHashSet<>();
            if (permissions != null) {
                for (Object item : permissions) {
                    JSONObject permission = (JSONObject) item;
                    String roleCode = permission.getString("roleCode");
                    String repoName = permission.getString("repo");
                    Long agentId = objectId(objects, "agents", roleCode);
                    Long repoId = repos.get(repoName);
                    runStep(run, onboarding, ++stepNo, "permission:" + roleCode + ":" + repoName, "REPO_PERMISSION",
                            () -> grantPermission(agentId, repoId, permission.getString("permLevel"), workspaceId, userId));
                    touchedRoles.add(roleCode);
                }
            }
            JSONArray bindings = changes.getJSONArray("skillBindings");
            if (bindings != null) {
                for (Object item : bindings) {
                    JSONObject binding = (JSONObject) item;
                    String roleCode = binding.getString("roleCode");
                    String skillName = binding.getString("skillName");
                    Long agentId = objectId(objects, "agents", roleCode);
                    Long skillId = ensureSkillId(skillName, workspaceId);
                    runStep(run, onboarding, ++stepNo, "skill-binding:" + roleCode + ":" + skillName, "SKILL_BINDING",
                            () -> bindSkill(agentId, skillId, workspaceId, userId));
                    touchedRoles.add(roleCode);
                }
            }
            JSONArray instructions = changes.getJSONArray("sdlcInstructions");
            if (instructions != null) {
                for (Object item : instructions) {
                    JSONObject instruction = (JSONObject) item;
                    String roleCode = instruction.getString("roleCode");
                    String stepCode = instruction.getString("stepCode");
                    Long sdlcId = objectId(objects, "sdcls", roleCode);
                    runStep(run, onboarding, ++stepNo, "sdlc-instruction:" + roleCode + ":" + stepCode, "SDLC_STEP",
                            () -> updateInstruction(onboarding, objects, reusedSdcls, ownedSdcls, roleCode, sdlcId,
                                    stepCode, instruction.getString("instructionMd"), workspaceId, userId));
                }
            }
            for (String roleCode : touchedRoles) {
                Long agentId = objectId(objects, "agents", roleCode);
                runStep(run, onboarding, ++stepNo, "republish:" + roleCode, "AGENT_PUBLISH",
                        () -> republish(agentId, workspaceId, userId));
            }
            Map<String, Object> applied = new LinkedHashMap<>();
            applied.put("repositories", repositories != null && !repositories.isEmpty());
            applied.put("repoPermissions", permissions != null && !permissions.isEmpty());
            Map<String, Object> variableValues = new LinkedHashMap<>();
            if (variables != null) {
                for (Object item : variables) {
                    JSONObject variable = (JSONObject) item;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("value", variable.get("value"));
                    entry.put("notApplicable", variable.get("notApplicable"));
                    entry.put("reason", variable.get("reason"));
                    variableValues.put(variable.getString("token"), entry);
                }
            }
            String resultJson = JSON.toJSONString(applied);
            dao.updateRun(run.getId(), workspaceId, "SUCCEEDED", resultJson, null);
            run.setStatus("SUCCEEDED");
            run.setResultJson(resultJson);
            Set<String> keys = new LinkedHashSet<>();
            if (Boolean.TRUE.equals(applied.get("repositories"))) {
                keys.add("repositories");
            }
            if (Boolean.TRUE.equals(applied.get("repoPermissions"))) {
                keys.add("repoPermissions");
            }
            onboardingService.recordAppliedConfig(onboarding, userId, keys, variableValues);
            onboardingService.recordDraftSection(onboarding, userId, "repositories", repos);
            if (objects != null) {
                onboardingService.recordDraftSection(onboarding, userId, "objects", objects);
            }
            onboardingService.advanceStage(onboarding, userId, WorkspaceOnboardingService.STAGE_RUNTIME_PENDING);
            onboardingService.bumpConfigVersion(onboarding, userId);
            onboardingService.refreshSteps(onboarding);
            return runView(run);
        } catch (RuntimeException failure) {
            String failureJson = failure instanceof BizException biz
                    ? biz.getCode() + ":" + biz.getMessage()
                    : failure.getClass().getSimpleName() + ":" + failure.getMessage();
            dao.updateRun(run.getId(), workspaceId, "FAILED", run.getResultJson(), failureJson);
            run.setStatus("FAILED");
            run.setFailureJson(failureJson);
            return runView(run);
        }
    }

    private void applyVariables(WorkspaceOnboardingDO onboarding, JSONArray variables, JSONObject objects,
            Set<String> reusedSdcls, Set<String> ownedSdcls, HarnessRunDO run, int stepNo, long workspaceId,
            long userId) {
        runStep(run, onboarding, stepNo, "variables", "VARIABLES", () -> {
            Map<String, String> replacements = new LinkedHashMap<>();
            for (Object item : variables) {
                JSONObject variable = (JSONObject) item;
                String value = variable.getString("value");
                if (value != null && !value.isBlank()) {
                    replacements.put(variable.getString("token"), value);
                }
            }
            if (replacements.isEmpty() || objects == null) {
                return 0L;
            }
            Map<String, String> previousValues = appliedValues(onboarding);
            JSONObject appliedSteps = appliedSteps(onboarding);
            JSONObject sdcls = objects.getJSONObject("sdcls");
            long updated = 0;
            if (sdcls != null) {
                for (String roleCode : new ArrayList<>(sdcls.keySet())) {
                    Object raw = sdcls.get(roleCode);
                    if (!(raw instanceof Number number)) {
                        continue;
                    }
                    long sdlcId = ensureOwnedSdlc(onboarding, objects, reusedSdcls, ownedSdcls, roleCode,
                            number.longValue(), workspaceId, userId);
                    sdcls.put(roleCode, sdlcId);
                    SdlcVO sdlc = sdlcService.get(sdlcId);
                    for (StepVO step : sdlc.getSteps()) {
                        String instruction = step.getInstructionMd();
                        String replaced = revise(instruction, step.getId(), replacements, previousValues,
                                appliedSteps);
                        if (!replaced.equals(instruction)) {
                            UpdateStepRequest request = new UpdateStepRequest();
                            request.setInstructionMd(replaced);
                            sdlcService.updateStep(sdlcId, step.getId(), request, workspaceId, userId);
                            updated++;
                        }
                    }
                }
            }
            if (!appliedSteps.isEmpty()) {
                onboardingService.recordDraftSection(onboarding, userId, "appliedVariables", appliedSteps);
            }
            return updated;
        });
    }

    /**
     * 首次应用替换占位符；修订时只改「此前记录过该变量的步骤」，且要求旧值在该步骤内唯一，
     * 无法唯一定位时拒绝并提示重新预检，避免全局替换旧值破坏无关文本。
     */
    private String revise(String text, long stepId, Map<String, String> replacements,
            Map<String, String> previousValues, JSONObject appliedSteps) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String token = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            if (result.contains(token)) {
                result = result.replace(token, value);
                recordAppliedStep(appliedSteps, token, stepId, value);
                continue;
            }
            String previous = previousValues.get(token);
            if (previous == null || previous.isBlank() || previous.equals(value)
                    || !isAppliedStep(appliedSteps, token, stepId)) {
                continue;
            }
            int occurrences = occurrences(result, previous);
            if (occurrences == 0) {
                if (!result.contains(value)) {
                    throw BizException.keyed(ErrorCode.HARNESS_CONFIGURATION_INVALID,
                            "harness.config.applied_value_missing", new Object[]{token},
                            "无法定位上次应用的变量值，请重新预检");
                }
                continue;
            }
            if (occurrences > 1) {
                throw BizException.keyed(ErrorCode.HARNESS_CONFIGURATION_INVALID,
                        "harness.config.applied_value_ambiguous", new Object[]{token},
                        "上次应用的变量值在该步骤中不唯一，请重新预检");
            }
            int index = result.indexOf(previous);
            result = result.substring(0, index) + value + result.substring(index + previous.length());
            recordAppliedStep(appliedSteps, token, stepId, value);
        }
        return result;
    }

    private int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private JSONObject appliedSteps(WorkspaceOnboardingDO onboarding) {
        JSONObject stored = onboardingService.draft(onboarding).getJSONObject("appliedVariables");
        return stored == null ? new JSONObject() : new JSONObject(stored);
    }

    private boolean isAppliedStep(JSONObject appliedSteps, String token, long stepId) {
        JSONObject entry = appliedSteps.getJSONObject(token);
        if (entry == null) {
            return false;
        }
        Object raw = entry.get("steps");
        if (!(raw instanceof List<?> steps)) {
            return false;
        }
        return steps.stream().anyMatch(step -> String.valueOf(step).equals(String.valueOf(stepId)));
    }

    private void recordAppliedStep(JSONObject appliedSteps, String token, long stepId, String value) {
        JSONObject entry = appliedSteps.getJSONObject(token);
        LinkedHashSet<String> steps = new LinkedHashSet<>();
        if (entry != null && entry.get("steps") instanceof List<?> existing) {
            existing.forEach(step -> steps.add(String.valueOf(step)));
        }
        steps.add(String.valueOf(stepId));
        JSONObject updated = entry == null ? new JSONObject() : new JSONObject(entry);
        updated.put("value", value);
        updated.put("steps", new ArrayList<>(steps));
        appliedSteps.put(token, updated);
    }

    private Set<String> stringSet(JSONObject objects, String section) {
        Set<String> values = new LinkedHashSet<>();
        if (objects == null) {
            return values;
        }
        Object raw = objects.get(section);
        if (raw instanceof List<?> list) {
            list.forEach(item -> values.add(String.valueOf(item)));
        }
        return values;
    }

    private Map<String, String> appliedValues(WorkspaceOnboardingDO onboarding) {
        Map<String, String> values = new LinkedHashMap<>();
        JSONObject variables = onboardingService.draft(onboarding).getJSONObject("variables");
        if (variables != null) {
            variables.forEach((token, value) -> {
                if (value instanceof Map<?, ?> entry && entry.get("value") != null) {
                    values.put(token, String.valueOf(entry.get("value")));
                }
            });
        }
        return values;
    }

    /**
     * 配置写入只允许作用于本引导创建的流程；复用到的是同空间共享对象时，先复制成本实例副本、
     * 重绑对应数字人并发布，再修改副本。复制结果与 owned 标记同步落库，后续批次不会重复复制。
     */
    private long ensureOwnedSdlc(WorkspaceOnboardingDO onboarding, JSONObject objects, Set<String> reusedSdcls,
            Set<String> ownedSdcls, String roleCode, long sdlcId, long workspaceId, long userId) {
        if (ownedSdcls.contains(roleCode)) {
            return sdlcId;
        }
        if (reusedSdcls == null || !reusedSdcls.contains(roleCode)) {
            return sdlcId;
        }
        String instanceSuffix = "（实例 " + onboarding.getId() + "）";
        SdlcVO current = sdlcService.get(sdlcId);
        if (current == null) {
            throw BizException.keyed(ErrorCode.HARNESS_CONFIGURATION_INVALID, "harness.config.step_unknown",
                    new Object[]{sdlcId}, "流程不存在，请重新预检");
        }
        if (current.getName() != null && current.getName().endsWith(instanceSuffix)) {
            // 兼容上一版遗留：对象已指向实例副本，只补写标记。
            markOwned(onboarding, objects, reusedSdcls, ownedSdcls, roleCode);
            return sdlcId;
        }
        CreateSdlcRequest copyRequest = new CreateSdlcRequest();
        copyRequest.setName(current.getName() + instanceSuffix);
        copyRequest.setDescription(current.getDescription());
        copyRequest.setWorkType(current.getWorkType());
        SdlcVO copy = sdlcService.create(copyRequest, workspaceId, userId);
        if (current.getSteps() != null) {
            for (StepVO step : current.getSteps()) {
                sdlcService.addStep(copy.getId(), copyStepRequest(step), workspaceId, userId);
            }
        }
        JSONObject sdcls = objects.getJSONObject("sdcls");
        if (sdcls != null) {
            for (String role : new ArrayList<>(sdcls.keySet())) {
                Object value = sdcls.get(role);
                if (value instanceof Number number && number.longValue() == sdlcId) {
                    sdcls.put(role, copy.getId());
                    Long agentId = objectId(objects, "agents", role);
                    if (agentId != null) {
                        rebindSdlc(agentId, copy.getId(), workspaceId, userId);
                    }
                }
            }
        }
        reusedSdcls.remove(roleCode);
        ownedSdcls.add(roleCode);
        markOwned(onboarding, objects, reusedSdcls, ownedSdcls, roleCode);
        return copy.getId();
    }

    private void markOwned(WorkspaceOnboardingDO onboarding, JSONObject objects, Set<String> reusedSdcls,
            Set<String> ownedSdcls, String roleCode) {
        reusedSdcls.remove(roleCode);
        ownedSdcls.add(roleCode);
        objects.put("reusedSdcls", List.copyOf(reusedSdcls));
        objects.put("ownedSdcls", List.copyOf(ownedSdcls));
        onboardingService.recordDraftSection(onboarding, onboarding.getOwnerUserId(), "objects", objects);
    }

    private CreateStepRequest copyStepRequest(StepVO step) {
        CreateStepRequest stepRequest = new CreateStepRequest();
        stepRequest.setStepOrder(step.getStepOrder());
        stepRequest.setName(step.getName());
        stepRequest.setKind(step.getKind());
        stepRequest.setInstructionMd(step.getInstructionMd());
        stepRequest.setChecklistJson(step.getChecklistJson());
        stepRequest.setGatePolicyJson(step.getGatePolicyJson());
        stepRequest.setRequired(step.getRequired());
        stepRequest.setTimeoutSeconds(step.getTimeoutSeconds());
        stepRequest.setRetryBudget(step.getRetryBudget());
        stepRequest.setCode(step.getCode());
        stepRequest.setHandlerType(step.getHandlerType());
        stepRequest.setHandlerRoleRef(step.getHandlerRoleRef());
        stepRequest.setStatusOnEnterCode(step.getStatusOnEnterCode());
        stepRequest.setOnSuccess(step.getOnSuccess());
        stepRequest.setOnFail(step.getOnFail());
        return stepRequest;
    }

    private void rebindSdlc(long agentId, long sdlcId, long workspaceId, long userId) {
        UpdateConfigRequest request = new UpdateConfigRequest();
        request.setSdlcId(sdlcId);
        request.setProvidedFields(java.util.Set.of("sdlcId"));
        agentService.editConfig(agentId, request, workspaceId, userId);
        republish(agentId, workspaceId, userId);
    }

    private Long ensureRepo(JSONObject repo, long workspaceId, long userId) {
        String name = repo.getString("name");
        RepoVO existing = repoService.list(workspaceId, 1, 100).stream()
                .filter(item -> name.equals(item.getName()))
                .findFirst().orElse(null);
        if (existing != null) {
            if (!Objects.equals(existing.getUrl(), repo.getString("url"))) {
                throw BizException.keyed(ErrorCode.HARNESS_CONFIGURATION_INVALID, "harness.config.repo_invalid",
                        new Object[]{name}, "同名仓库地址不一致");
            }
            return existing.getId();
        }
        CreateRepoRequest request = new CreateRepoRequest();
        request.setName(name);
        request.setUrl(repo.getString("url"));
        request.setDefaultBranch(repo.getString("defaultBranch"));
        request.setDescription(repo.getString("description"));
        return repoService.create(request, workspaceId, userId).getId();
    }

    private Long ensureRelation(Long fromRepoId, Long toRepoId, JSONObject relation, long workspaceId, long userId) {
        CreateRelationRequest request = new CreateRelationRequest();
        request.setFromRepoId(fromRepoId);
        request.setToRepoId(toRepoId);
        request.setRelationType("DEPENDS_ON");
        request.setDescription(relation.getString("description"));
        try {
            return repoService.createRelation(request, workspaceId, userId).getId();
        } catch (BizException duplicate) {
            if (!ErrorCode.CONFLICT.getCode().equals(duplicate.getCode())) {
                throw duplicate;
            }
            return fromRepoId;
        }
    }

    private Long grantPermission(long agentId, long repoId, String permLevel, long workspaceId, long userId) {
        RepoPermRequest request = new RepoPermRequest();
        request.setRepoId(repoId);
        request.setPermLevel(permLevel);
        agentService.addRepoPerm(agentId, request, workspaceId, userId);
        return repoId;
    }

    private Long bindSkill(long agentId, long skillId, long workspaceId, long userId) {
        SkillRequest request = new SkillRequest();
        request.setSkillId(skillId);
        agentService.addSkill(agentId, request, workspaceId, userId);
        return skillId;
    }

    private Long updateInstruction(WorkspaceOnboardingDO onboarding, JSONObject objects, Set<String> reusedSdcls,
            Set<String> ownedSdcls, String roleCode, long sdlcId, String stepCode, String instruction,
            long workspaceId, long userId) {
        long ownedSdlcId = ensureOwnedSdlc(onboarding, objects, reusedSdcls, ownedSdcls, roleCode, sdlcId,
                workspaceId, userId);
        SdlcVO sdlc = sdlcService.get(ownedSdlcId);
        StepVO step = (sdlc.getSteps() == null ? List.<StepVO>of() : sdlc.getSteps()).stream()
                .filter(item -> stepCode.equals(item.getCode()))
                .findFirst().orElseThrow(() -> BizException.keyed(ErrorCode.HARNESS_CONFIGURATION_INVALID,
                        "harness.config.step_unknown", new Object[]{stepCode}, "SDLC 步骤不存在"));
        UpdateStepRequest request = new UpdateStepRequest();
        request.setInstructionMd(instruction);
        sdlcService.updateStep(ownedSdlcId, step.getId(), request, workspaceId, userId);
        return step.getId();
    }

    private Long republish(long agentId, long workspaceId, long userId) {
        try {
            agentService.submit(agentId, workspaceId, userId);
            agentService.approve(agentId, workspaceId, userId, null);
        } catch (RuntimeException failure) {
            AgentVO agent = agentService.get(agentId, workspaceId);
            if (!Boolean.TRUE.equals(agent.isHasDraft()) && "ONLINE".equals(agent.getStatus())) {
                return agentId;
            }
            throw failure;
        }
        return agentId;
    }

    private Long ensureSkillId(String skillName, long workspaceId) {
        SkillVO skill = skillService.list(workspaceId, "SKILL", 1, 100).stream()
                .filter(item -> skillName.equals(item.getName()))
                .findFirst().orElse(null);
        if (skill == null) {
            throw BizException.keyed(ErrorCode.HARNESS_CONFIGURATION_INVALID, "harness.readiness.skill_missing",
                    new Object[]{"-", skillName}, "Skill 不在当前空间");
        }
        return skill.getId();
    }

    private Long runStep(HarnessRunDO run, WorkspaceOnboardingDO onboarding, int stepNo, String objectKey,
            String objectType, java.util.function.Supplier<Long> action) {
        HarnessRunStepDO stored = dao.findRunStep(run.getTenantId(), run.getId(), objectKey);
        if (stored != null && "SUCCEEDED".equals(stored.getStatus())) {
            return stored.getObjectId();
        }
        HarnessRunStepDO reusable = dao.findReusableRunStep(run.getTenantId(), onboarding.getId(),
                run.getRunType(), objectKey, run.getSnapshotId());
        if (reusable != null) {
            recordStep(run, stepNo, objectKey, objectType, "SUCCEEDED", reusable.getObjectId(), null);
            return reusable.getObjectId();
        }
        try {
            Long objectId = action.get();
            recordStep(run, stepNo, objectKey, objectType, "SUCCEEDED", objectId, null);
            return objectId;
        } catch (RuntimeException failure) {
            recordStep(run, stepNo, objectKey, objectType, "FAILED", null,
                    truncate(failure.getClass().getSimpleName() + ":" + failure.getMessage()));
            throw failure;
        }
    }

    private void recordStep(HarnessRunDO run, int stepNo, String objectKey, String objectType, String status,
            Long objectId, String error) {
        HarnessRunStepDO existing = dao.findRunStep(run.getTenantId(), run.getId(), objectKey);
        if (existing != null) {
            dao.updateRunStep(existing.getId(), run.getTenantId(), status, objectId, error);
            return;
        }
        HarnessRunStepDO step = new HarnessRunStepDO();
        step.setTenantId(run.getTenantId());
        step.setRunId(run.getId());
        step.setStepNo(stepNo);
        step.setObjectKey(objectKey);
        step.setObjectType(objectType);
        step.setStatus(status);
        step.setObjectId(objectId);
        step.setErrorMessage(error);
        dao.insertRunStep(step);
    }

    private Map<String, Object> runView(HarnessRunDO run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", String.valueOf(run.getId()));
        view.put("onboardingId", String.valueOf(run.getOnboardingId()));
        view.put("runType", run.getRunType());
        view.put("status", run.getStatus());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (HarnessRunStepDO step : dao.listRunSteps(run.getTenantId(), run.getId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("objectKey", step.getObjectKey());
            item.put("objectType", step.getObjectType());
            item.put("status", step.getStatus());
            item.put("objectId", step.getObjectId() == null ? null : String.valueOf(step.getObjectId()));
            item.put("error", step.getErrorMessage());
            steps.add(item);
        }
        view.put("steps", steps);
        view.put("result", run.getResultJson() == null ? null : JSON.parse(run.getResultJson()));
        view.put("failure", run.getFailureJson());
        return view;
    }

    private void normalizeVariables(Map<String, Object> changes, HarnessTemplate template, JSONObject normalized,
            List<Map<String, Object>> conflicts) {
        Object raw = changes.get("variables");
        if (raw == null) {
            return;
        }
        if (!(raw instanceof List<?> list)) {
            throw new BizException(ErrorCode.HARNESS_CONFIGURATION_INVALID);
        }
        List<String> tokens = template.variableTokens();
        JSONArray normalizedVariables = new JSONArray();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new BizException(ErrorCode.HARNESS_CONFIGURATION_INVALID);
            }
            JSONObject variable = new JSONObject(map);
            String token = variable.getString("token");
            if (token == null || !tokens.contains(token)) {
                conflicts.add(conflict("VARIABLE_UNKNOWN", token, "模板未声明该变量"));
                continue;
            }
            boolean notApplicable = Boolean.TRUE.equals(variable.getBoolean("notApplicable"));
            String value = variable.getString("value");
            if (notApplicable) {
                if (variable.getString("reason") == null || variable.getString("reason").isBlank()) {
                    conflicts.add(conflict("VARIABLE_REASON_REQUIRED", token, "不适用必须说明理由"));
                    continue;
                }
            } else if (value == null || value.isBlank()) {
                conflicts.add(conflict("VARIABLE_VALUE_REQUIRED", token, "变量取值不能为空"));
                continue;
            } else if (value.contains("{{")) {
                conflicts.add(conflict("VARIABLE_UNRESOLVED", token, "取值仍包含未解析占位符"));
                continue;
            }
            normalizedVariables.add(variable);
        }
        normalized.put("variables", normalizedVariables);
    }

    private void normalizeRepositories(Map<String, Object> changes, JSONObject normalized,
            List<Map<String, Object>> conflicts, Map<String, Object> knownRepos) {
        Object raw = changes.get("repositories");
        if (raw == null) {
            return;
        }
        if (!(raw instanceof List<?> list)) {
            throw new BizException(ErrorCode.HARNESS_CONFIGURATION_INVALID);
        }
        JSONArray repositories = new JSONArray();
        for (Object item : list) {
            JSONObject repo = new JSONObject(asMap(item));
            String name = repo.getString("name");
            if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")
                    || ".".equals(name) || "..".equals(name)) {
                conflicts.add(conflict("REPOSITORY_NAME_INVALID", name, "仓库名必须是单段路径"));
                continue;
            }
            if (repo.getString("url") == null || repo.getString("url").isBlank()
                    || repo.getString("defaultBranch") == null || repo.getString("defaultBranch").isBlank()) {
                conflicts.add(conflict("REPOSITORY_INCOMPLETE", name, "仓库缺少地址或默认分支"));
                continue;
            }
            repositories.add(repo);
            knownRepos.putIfAbsent(name, 0);
        }
        normalized.put("repositories", repositories);
    }

    private void normalizeRelations(Map<String, Object> changes, JSONObject normalized,
            List<Map<String, Object>> conflicts, Map<String, Object> knownRepos) {
        Object raw = changes.get("relations");
        if (raw == null) {
            return;
        }
        if (!(raw instanceof List<?> list)) {
            throw new BizException(ErrorCode.HARNESS_CONFIGURATION_INVALID);
        }
        JSONArray relations = new JSONArray();
        for (Object item : list) {
            JSONObject relation = new JSONObject(asMap(item));
            String from = relation.getString("from");
            String to = relation.getString("to");
            if (from == null || to == null || !knownRepos.containsKey(from) || !knownRepos.containsKey(to)) {
                conflicts.add(conflict("RELATION_ENDPOINT_UNKNOWN", from, "依赖关系引用了未登记的仓库"));
                continue;
            }
            relations.add(relation);
        }
        normalized.put("relations", relations);
    }

    private void normalizePermissions(Map<String, Object> changes, HarnessTemplate template, JSONObject normalized,
            List<Map<String, Object>> conflicts, Map<String, Object> knownRepos, WorkspaceOnboardingDO onboarding) {
        normalizePermissions(changes, template, normalized, conflicts, knownRepos, onboarding, true);
    }

    private void normalizePermissions(Map<String, Object> changes, HarnessTemplate template, JSONObject normalized,
            List<Map<String, Object>> conflicts, Map<String, Object> knownRepos, WorkspaceOnboardingDO onboarding,
            boolean requireAgent) {
        Object raw = changes.get("repoPermissions");
        if (raw == null) {
            return;
        }
        if (!(raw instanceof List<?> list)) {
            throw new BizException(ErrorCode.HARNESS_CONFIGURATION_INVALID);
        }
        JSONObject objects = onboardingService.draft(onboarding).getJSONObject("objects");
        JSONArray permissions = new JSONArray();
        for (Object item : list) {
            JSONObject permission = new JSONObject(asMap(item));
            String roleCode = permission.getString("roleCode");
            String repoName = permission.getString("repo");
            String permLevel = permission.getString("permLevel");
            if (template.agent(roleCode) == null) {
                conflicts.add(conflict("PERMISSION_ROLE_UNKNOWN", roleCode, "角色不在模板小队中"));
                continue;
            }
            if (!knownRepos.containsKey(repoName)) {
                conflicts.add(conflict("PERMISSION_REPO_UNKNOWN", repoName, "仓库未登记"));
                continue;
            }
            String expected = catalog.repoPermissionFor(roleCode);
            if (expected == null || !expected.equalsIgnoreCase(permLevel)) {
                conflicts.add(conflict("PERMISSION_LEVEL_MISMATCH", roleCode,
                        "角色权限应为 " + expected + "，不允许授予更高权限"));
                continue;
            }
            if (requireAgent && objectId(objects, "agents", roleCode) == null) {
                conflicts.add(conflict("PERMISSION_AGENT_MISSING", roleCode, "模板尚未创建该数字人"));
                continue;
            }
            permission.put("permLevel", expected);
            permissions.add(permission);
        }
        normalized.put("repoPermissions", permissions);
    }

    private void normalizeSkillBindings(Map<String, Object> changes, HarnessTemplate template,
            JSONObject normalized, List<Map<String, Object>> conflicts) {
        Object raw = changes.get("skillBindings");
        if (raw == null) {
            return;
        }
        if (!(raw instanceof List<?> list)) {
            throw new BizException(ErrorCode.HARNESS_CONFIGURATION_INVALID);
        }
        JSONArray bindings = new JSONArray();
        for (Object item : list) {
            JSONObject binding = new JSONObject(asMap(item));
            String roleCode = binding.getString("roleCode");
            if (template.agent(roleCode) == null) {
                conflicts.add(conflict("SKILL_BINDING_ROLE_UNKNOWN", roleCode, "角色不在模板小队中"));
                continue;
            }
            if (binding.getString("skillName") == null || binding.getString("skillName").isBlank()) {
                conflicts.add(conflict("SKILL_BINDING_NAME_REQUIRED", roleCode, "Skill 名称不能为空"));
                continue;
            }
            bindings.add(binding);
        }
        normalized.put("skillBindings", bindings);
    }

    private void normalizeSdlcInstructions(Map<String, Object> changes, HarnessTemplate template,
            JSONObject normalized, List<Map<String, Object>> conflicts) {
        Object raw = changes.get("sdlcInstructions");
        if (raw == null) {
            return;
        }
        if (!(raw instanceof List<?> list)) {
            throw new BizException(ErrorCode.HARNESS_CONFIGURATION_INVALID);
        }
        JSONArray instructions = new JSONArray();
        for (Object item : list) {
            JSONObject instruction = new JSONObject(asMap(item));
            String roleCode = instruction.getString("roleCode");
            String stepCode = instruction.getString("stepCode");
            HarnessAgent agent = template.agent(roleCode);
            if (agent == null || agent.sdlc() == null
                    || agent.sdlc().steps().stream().noneMatch(step -> step.code().equals(stepCode))) {
                conflicts.add(conflict("SDLC_STEP_UNKNOWN", roleCode, "SDLC 步骤不存在"));
                continue;
            }
            if (instruction.getString("instructionMd") == null
                    || instruction.getString("instructionMd").isBlank()) {
                conflicts.add(conflict("SDLC_INSTRUCTION_REQUIRED", roleCode, "SDLC 指令不能为空"));
                continue;
            }
            instructions.add(instruction);
        }
        normalized.put("sdlcInstructions", instructions);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new BizException(ErrorCode.HARNESS_CONFIGURATION_INVALID);
        }
        return (Map<String, Object>) map;
    }

    private Map<String, Object> knownRepos(WorkspaceOnboardingDO onboarding) {
        Map<String, Object> repos = new LinkedHashMap<>();
        JSONObject stored = onboardingService.draft(onboarding).getJSONObject("repositories");
        if (stored != null) {
            stored.forEach(repos::put);
        }
        return repos;
    }

    private Long objectId(JSONObject objects, String section, String key) {
        if (objects == null) {
            return null;
        }
        JSONObject group = objects.getJSONObject(section);
        if (group == null) {
            return null;
        }
        Object value = group.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private void requireInitialized(WorkspaceOnboardingDO onboarding) {
        if (WorkspaceOnboardingService.STAGE_DRAFT.equals(onboarding.getStage())
                || WorkspaceOnboardingService.STAGE_INITIALIZING.equals(onboarding.getStage())) {
            throw new BizException(ErrorCode.HARNESS_RUN_STATE_INVALID);
        }
    }

    private int size(JSONObject value, String key) {
        JSONArray array = value.getJSONArray(key);
        return array == null ? 0 : array.size();
    }

    private Map<String, Object> keyedConflict(String code, String field, String messageKey, String diagnostic) {
        return conflict(code, field, SystemMessages.render(messageKey, null, diagnostic));
    }

    private Map<String, Object> conflict(String code, String field, String message) {
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("code", code);
        conflict.put("field", field);
        conflict.put("message", message);
        return conflict;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private long required(Long value, String field) {
        if (value == null) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return value;
    }
}
