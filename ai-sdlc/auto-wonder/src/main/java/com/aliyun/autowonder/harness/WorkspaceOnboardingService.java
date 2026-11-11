package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 引导草稿与稳定步骤。草稿只保存准备数据，不能直接设置业务步骤完成或 READY；
 * 步骤完成状态来自真实写入结果、就绪检查与客户确认。
 */
@Service
public class WorkspaceOnboardingService {

    public static final String STAGE_DRAFT = "DRAFT";
    public static final String STAGE_INITIALIZING = "INITIALIZING";
    public static final String STAGE_TEMPLATE_INITIALIZED = "TEMPLATE_INITIALIZED";
    public static final String STAGE_CONFIGURING = "CONFIGURING";
    public static final String STAGE_RUNTIME_PENDING = "RUNTIME_PENDING";
    public static final String STAGE_READY = "READY";

    public static final String STEP_SELECT_TEMPLATE = "select_template";
    public static final String STEP_CONFIRM_INITIALIZATION = "confirm_initialization";
    public static final String STEP_REPOSITORIES = "repositories";
    public static final String STEP_REPO_PERMISSIONS = "repo_permissions";
    public static final String STEP_EXECUTOR_RUNTIME = "executor_runtime";
    public static final String STEP_FINAL_READINESS = "final_readiness";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String STATUS_BLOCKED = "BLOCKED";

    private static final Set<String> STAGES = Set.of(STAGE_DRAFT, STAGE_INITIALIZING,
            STAGE_TEMPLATE_INITIALIZED, STAGE_CONFIGURING, STAGE_RUNTIME_PENDING, STAGE_READY);
    private static final Set<String> DRAFT_FIELDS = Set.of("templateKey", "templateVersion", "templateSha256",
            "clientKind", "variables", "config", "evidence", "currentStepKey", "paused", "confirmCheckId");

    private final WorkspaceOnboardingDao dao;
    private final HarnessTemplateCatalog catalog;

    public WorkspaceOnboardingService(WorkspaceOnboardingDao dao, HarnessTemplateCatalog catalog) {
        this.dao = dao;
        this.catalog = catalog;
    }

    public record StepView(String stepKey, String name, String kind, int order, String status,
            String reason, String evidence) {
    }

    public record OnboardingView(long id, long workspaceId, long ownerUserId, String templateKey,
            String templateVersion, String templateSha256, String stage, boolean paused, boolean estimated,
            int version, int configVersion, int totalSteps, int completedSteps, int remainingSteps,
            int currentStepIndex, Map<String, Object> draft, List<StepView> steps, List<String> gaps) {
    }

    public record PrepareResult(OnboardingView onboarding, boolean created) {
    }

    /**
     * 创建或更新引导草稿。已有草稿必须带 expectedVersion，requestKey 相同的重复请求返回同一草稿。
     */
    @Transactional
    public PrepareResult prepare(long workspaceId, long userId, Map<String, Object> args) {
        Long onboardingId = longArg(args, "onboardingId");
        Map<String, Object> draft = mapArg(args, "draft");
        if (draft == null) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        validateDraftFields(draft);
        if (draft.containsKey("paused") && !(draft.get("paused") instanceof Boolean)) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        if (onboardingId != null) {
            Integer expectedVersion = intArg(args, "expectedVersion");
            if (expectedVersion == null) {
                throw BizException.keyed(ErrorCode.MCP_TOOL_ARGUMENT_INVALID,
                        "harness.onboarding.version_required", null, "expectedVersion 必填");
            }
            WorkspaceOnboardingDO existing = require(workspaceId, userId, onboardingId);
            if (!expectedVersion.equals(existing.getVersion())) {
                throw new BizException(ErrorCode.HARNESS_ONBOARDING_VERSION_CONFLICT);
            }
            return new PrepareResult(updateDraft(existing, draft, userId, true), false);
        }
        String requestKey = stringArg(args, "requestKey");
        if (requestKey != null && !requestKey.isBlank()) {
            WorkspaceOnboardingDO previous = dao.findOnboardingByRequestKey(workspaceId, userId, requestKey);
            if (previous != null) {
                return new PrepareResult(view(previous), false);
            }
        }
        WorkspaceOnboardingDO created = new WorkspaceOnboardingDO();
        created.setTenantId(workspaceId);
        created.setWorkspaceId(workspaceId);
        created.setOwnerUserId(userId);
        created.setStage(STAGE_DRAFT);
        created.setPaused(Boolean.FALSE);
        created.setConfigVersion(0);
        created.setCurrentStepIndex(0);
        created.setRequestKey(requestKey);
        created.setCreatorId(userId);
        created.setVersion(0);
        JSONObject merged = merge(new JSONObject(), draft);
        applyTemplateSelection(created, merged);
        List<GeneratedStep> generated = generateSteps(created, merged);
        created.setStepsTotal(generated.size());
        created.setStepsEstimated(created.getTemplateKey() == null);
        created.setDraftJson(JSON.toJSONString(merged));
        dao.insertOnboarding(created);
        for (int index = 0; index < generated.size(); index++) {
            dao.insertStep(toDO(created, generated.get(index), index + 1));
        }
        return new PrepareResult(view(created), true);
    }

    /** 读取草稿；未指定 onboardingId 时返回当前空间本人可恢复的草稿摘要。 */
    public Map<String, Object> get(long workspaceId, long userId, Map<String, Object> args) {
        Long onboardingId = longArg(args, "onboardingId");
        Map<String, Object> result = new LinkedHashMap<>();
        if (onboardingId != null) {
            WorkspaceOnboardingDO onboarding = require(workspaceId, userId, onboardingId);
            result.put("onboarding", view(onboarding));
            result.put("onboardings", List.of(summary(onboarding)));
            return result;
        }
        List<WorkspaceOnboardingDO> onboardings = dao.listOnboardings(workspaceId, workspaceId, userId);
        if (onboardings.isEmpty()) {
            throw new BizException(ErrorCode.HARNESS_ONBOARDING_NOT_FOUND);
        }
        result.put("onboarding", view(onboardings.get(0)));
        result.put("onboardings", onboardings.stream().map(this::summary).toList());
        return result;
    }

    public WorkspaceOnboardingDO require(long workspaceId, long userId, long onboardingId) {
        WorkspaceOnboardingDO onboarding = dao.findOnboarding(onboardingId, workspaceId);
        if (onboarding == null || !onboarding.getOwnerUserId().equals(userId)) {
            throw new BizException(ErrorCode.HARNESS_ONBOARDING_NOT_FOUND);
        }
        return onboarding;
    }

    public WorkspaceOnboardingDO requireInWorkspace(long workspaceId, long onboardingId) {
        WorkspaceOnboardingDO onboarding = dao.findOnboarding(onboardingId, workspaceId);
        if (onboarding == null) {
            throw new BizException(ErrorCode.HARNESS_ONBOARDING_NOT_FOUND);
        }
        return onboarding;
    }

    public JSONObject draft(WorkspaceOnboardingDO onboarding) {
        return onboarding.getDraftJson() == null ? new JSONObject()
                : JSON.parseObject(onboarding.getDraftJson());
    }

    public OnboardingView view(WorkspaceOnboardingDO onboarding) {
        HarnessTemplate template = selectedTemplate(onboarding);
        List<OnboardingStepDO> stored = dao.listSteps(onboarding.getTenantId(), onboarding.getId());
        if (stored.isEmpty()) {
            return emptyView(onboarding);
        }
        JSONObject draft = draft(onboarding);
        List<String> gaps = new ArrayList<>();
        List<StepView> steps = new ArrayList<>();
        int completed = 0;
        int currentIndex = -1;
        int order = 0;
        for (OnboardingStepDO step : stored) {
            order++;
            StatusPair status = statusOf(step, onboarding, template, draft, gaps);
            if (STATUS_COMPLETED.equals(status.status()) || STATUS_NOT_APPLICABLE.equals(status.status())) {
                completed++;
            } else if (currentIndex < 0) {
                currentIndex = order - 1;
            }
            steps.add(new StepView(step.getStepKey(), step.getName(), step.getKind(), order,
                    status.status(), status.reason(), step.getEvidence()));
        }
        if (currentIndex < 0) {
            currentIndex = Math.max(0, steps.size() - 1);
        }
        return new OnboardingView(onboarding.getId(), onboarding.getWorkspaceId(), onboarding.getOwnerUserId(),
                onboarding.getTemplateKey(), onboarding.getTemplateVersion(), onboarding.getTemplateSha256(),
                onboarding.getStage(), Boolean.TRUE.equals(onboarding.getPaused()),
                Boolean.TRUE.equals(onboarding.getStepsEstimated()), intOrZero(onboarding.getVersion()),
                intOrZero(onboarding.getConfigVersion()), steps.size(), completed, steps.size() - completed,
                currentIndex, draft, steps, gaps);
    }

    /** 系统流程把阶段推进到目标值；与业务写入同一次调用，不暴露给草稿写入方。 */
    public void advanceStage(WorkspaceOnboardingDO onboarding, long userId, String stage) {
        if (!STAGES.contains(stage)) {
            throw BizException.keyed(ErrorCode.HARNESS_CONFIGURATION_INVALID, "harness.onboarding.stage_invalid",
                    new Object[]{stage}, "不支持的引导阶段");
        }
        if (onboarding.getStage().equals(stage)) {
            return;
        }
        update(onboarding, userId, stage, null, null, null);
    }

    public void bumpConfigVersion(WorkspaceOnboardingDO onboarding, long userId) {
        int next = onboarding.getConfigVersion() == null ? 1 : onboarding.getConfigVersion() + 1;
        update(onboarding, userId, onboarding.getStage(), null, next, null);
    }

    public void pause(WorkspaceOnboardingDO onboarding, long userId, boolean paused) {
        JSONObject draft = draft(onboarding);
        draft.put("paused", paused);
        update(onboarding, userId, onboarding.getStage(), draft, null, null);
    }

    public void recordEvidence(WorkspaceOnboardingDO onboarding, long userId, Map<String, Object> evidence) {
        JSONObject draft = draft(onboarding);
        JSONObject stored = draft.getJSONObject("evidence");
        JSONObject merged = stored == null ? new JSONObject() : new JSONObject(stored);
        merged.putAll(evidence);
        draft.put("evidence", merged);
        update(onboarding, userId, onboarding.getStage(), draft, null, null);
    }

    public void recordAppliedConfig(WorkspaceOnboardingDO onboarding, long userId, Set<String> appliedKeys,
            Map<String, Object> variables) {
        JSONObject draft = draft(onboarding);
        JSONObject applied = draft.getJSONObject("configApplied");
        JSONObject merged = applied == null ? new JSONObject() : new JSONObject(applied);
        for (String key : appliedKeys) {
            merged.put(key, true);
        }
        draft.put("configApplied", merged);
        if (variables != null && !variables.isEmpty()) {
            JSONObject storedVariables = draft.getJSONObject("variables");
            JSONObject mergedVariables = storedVariables == null ? new JSONObject() : new JSONObject(storedVariables);
            variables.forEach(mergedVariables::put);
            draft.put("variables", mergedVariables);
        }
        update(onboarding, userId, onboarding.getStage(), draft, null, null);
    }

    /** 保存对象映射或配置快照等内部段落；草稿写入方不能直接写这些字段。 */
    public void recordDraftSection(WorkspaceOnboardingDO onboarding, long userId, String key, Object value) {
        JSONObject draft = draft(onboarding);
        draft.put(key, value);
        update(onboarding, userId, onboarding.getStage(), draft, null, null);
    }

    /** 客户确认就绪后由服务端复核版本与在线状态，再写入 READY。 */
    public void markReady(WorkspaceOnboardingDO onboarding, long userId) {
        JSONObject draft = draft(onboarding);
        draft.put("paused", false);
        onboarding.setPaused(Boolean.FALSE);
        update(onboarding, userId, STAGE_READY, draft, null, null);
        refreshSteps(onboarding);
    }

    /** 把内存计算的步骤状态写回稳定步骤行，保证恢复与查询看到真实完成状态。 */
    public void refreshSteps(WorkspaceOnboardingDO onboarding) {
        HarnessTemplate template = selectedTemplate(onboarding);
        JSONObject draft = draft(onboarding);
        List<String> gaps = new ArrayList<>();
        for (OnboardingStepDO step : dao.listSteps(onboarding.getTenantId(), onboarding.getId())) {
            StatusPair status = statusOf(step, onboarding, template, draft, gaps);
            boolean reasonChanged = !java.util.Objects.equals(status.reason(), step.getReason());
            if (!status.status().equals(step.getStatus()) || reasonChanged) {
                dao.updateStepStatus(step.getId(), onboarding.getTenantId(), status.status(), status.reason(), null);
            }
        }
    }

    public HarnessTemplate selectedTemplate(WorkspaceOnboardingDO onboarding) {
        if (onboarding.getTemplateKey() == null) {
            List<HarnessTemplate> templates = catalog.list();
            return templates.isEmpty() ? null : templates.get(0);
        }
        return catalog.get(onboarding.getTemplateKey());
    }

    private OnboardingView emptyView(WorkspaceOnboardingDO onboarding) {
        return new OnboardingView(onboarding.getId(), onboarding.getWorkspaceId(), onboarding.getOwnerUserId(),
                onboarding.getTemplateKey(), onboarding.getTemplateVersion(), onboarding.getTemplateSha256(),
                onboarding.getStage(), Boolean.TRUE.equals(onboarding.getPaused()),
                Boolean.TRUE.equals(onboarding.getStepsEstimated()), intOrZero(onboarding.getVersion()),
                intOrZero(onboarding.getConfigVersion()), 0, 0, 0, 0, draft(onboarding), List.of(), List.of());
    }

    private static int intOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private Map<String, Object> summary(WorkspaceOnboardingDO onboarding) {
        OnboardingView view = view(onboarding);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("onboardingId", view.id());
        summary.put("templateKey", view.templateKey());
        summary.put("templateVersion", view.templateVersion());
        summary.put("stage", view.stage());
        summary.put("paused", view.paused());
        summary.put("version", view.version());
        summary.put("configVersion", view.configVersion());
        summary.put("totalSteps", view.totalSteps());
        summary.put("completedSteps", view.completedSteps());
        summary.put("remainingSteps", view.remainingSteps());
        summary.put("currentStepIndex", view.currentStepIndex());
        return summary;
    }

    protected OnboardingView updateDraft(WorkspaceOnboardingDO existing, Map<String, Object> patch, long userId,
            boolean rebuildSteps) {
        JSONObject merged = merge(draft(existing), patch);
        String templateKeyBefore = existing.getTemplateKey();
        applyTemplateSelection(existing, merged);
        boolean templateChanged = !java.util.Objects.equals(templateKeyBefore, existing.getTemplateKey());
        List<GeneratedStep> generated = generateSteps(existing, merged);
        existing.setStepsTotal(generated.size());
        existing.setStepsEstimated(existing.getTemplateKey() == null);
        existing.setDraftJson(JSON.toJSONString(merged));
        if (patch.containsKey("paused") && patch.get("paused") instanceof Boolean paused) {
            existing.setPaused(paused);
        }
        update(existing, userId, existing.getStage(), merged, null, null);
        if (rebuildSteps || templateChanged) {
            rebuildSteps(existing, generated);
        }
        refreshSteps(existing);
        return view(existing);
    }

    private void update(WorkspaceOnboardingDO onboarding, long userId, String stage, JSONObject draft,
            Integer configVersion, String ignored) {
        String draftJson = draft == null ? onboarding.getDraftJson() : JSON.toJSONString(draft);
        String effectiveStage = stage == null ? onboarding.getStage() : stage;
        int effectiveConfigVersion = configVersion == null
                ? (onboarding.getConfigVersion() == null ? 0 : onboarding.getConfigVersion())
                : configVersion;
        int rows = dao.updateOnboarding(onboarding.getId(), onboarding.getTenantId(), onboarding.getTemplateKey(),
                onboarding.getTemplateVersion(), onboarding.getTemplateSha256(), effectiveStage,
                Boolean.TRUE.equals(onboarding.getPaused()), effectiveConfigVersion, onboarding.getStepsTotal(),
                Boolean.TRUE.equals(onboarding.getStepsEstimated()), onboarding.getCurrentStepIndex(), draftJson,
                onboarding.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.HARNESS_ONBOARDING_VERSION_CONFLICT);
        }
        onboarding.setStage(effectiveStage);
        onboarding.setConfigVersion(effectiveConfigVersion);
        onboarding.setDraftJson(draftJson);
        onboarding.setVersion(onboarding.getVersion() == null ? 1 : onboarding.getVersion() + 1);
    }

    private void applyTemplateSelection(WorkspaceOnboardingDO onboarding, JSONObject draft) {
        String templateKey = draft.getString("templateKey");
        if (templateKey == null) {
            return;
        }
        HarnessTemplate template = catalog.require(templateKey, draft.getString("templateVersion"),
                draft.getString("templateSha256"));
        onboarding.setTemplateKey(template.getKey());
        onboarding.setTemplateVersion(template.getVersion());
        onboarding.setTemplateSha256(template.getSha256());
    }

    private void rebuildSteps(WorkspaceOnboardingDO onboarding, List<GeneratedStep> generated) {
        List<OnboardingStepDO> existing = dao.listSteps(onboarding.getTenantId(), onboarding.getId());
        Set<String> generatedKeys = new LinkedHashSet<>();
        generated.forEach(step -> generatedKeys.add(step.stepKey()));
        for (OnboardingStepDO step : existing) {
            if (!generatedKeys.contains(step.getStepKey())) {
                dao.deleteStep(step.getTenantId(), step.getOnboardingId(), step.getStepKey());
            }
        }
        Set<String> existingKeys = new LinkedHashSet<>();
        existing.forEach(step -> existingKeys.add(step.getStepKey()));
        for (int index = 0; index < generated.size(); index++) {
            GeneratedStep step = generated.get(index);
            if (!existingKeys.contains(step.stepKey())) {
                dao.insertStep(toDO(onboarding, step, index + 1));
            }
        }
    }

    private List<GeneratedStep> generateSteps(WorkspaceOnboardingDO onboarding, JSONObject draft) {
        HarnessTemplate template = selectedTemplate(onboarding);
        List<GeneratedStep> steps = new ArrayList<>();
        steps.add(new GeneratedStep(STEP_SELECT_TEMPLATE, "选择模板", "template"));
        steps.add(new GeneratedStep(STEP_CONFIRM_INITIALIZATION, "确认初始化", "initialization"));
        if (template != null) {
            for (HarnessVariable variable : template.getVariables()) {
                steps.add(new GeneratedStep(variableStepKey(variable.token()), variable.label(), "variable"));
            }
        }
        steps.add(new GeneratedStep(STEP_REPOSITORIES, "仓库与依赖关系配置", "repository"));
        steps.add(new GeneratedStep(STEP_REPO_PERMISSIONS, "仓库权限确认", "permission"));
        steps.add(new GeneratedStep(STEP_EXECUTOR_RUNTIME, "执行器运行准备", "runtime"));
        steps.add(new GeneratedStep(STEP_FINAL_READINESS, "最终就绪确认", "readiness"));
        return steps;
    }

    private StatusPair statusOf(OnboardingStepDO step, WorkspaceOnboardingDO onboarding, HarnessTemplate template,
            JSONObject draft, List<String> gaps) {
        String stage = onboarding.getStage();
        boolean initialized = !STAGE_DRAFT.equals(stage) && !STAGE_INITIALIZING.equals(stage);
        switch (step.getStepKey()) {
            case STEP_SELECT_TEMPLATE:
                return onboarding.getTemplateKey() == null
                        ? new StatusPair(STATUS_PENDING, null)
                        : new StatusPair(STATUS_COMPLETED, null);
            case STEP_CONFIRM_INITIALIZATION:
                if (STAGE_INITIALIZING.equals(stage)) {
                    return new StatusPair(STATUS_IN_PROGRESS, null);
                }
                return initialized ? new StatusPair(STATUS_COMPLETED, null) : new StatusPair(STATUS_PENDING, null);
            case STEP_REPOSITORIES:
                return applied(draft, "repositories");
            case STEP_REPO_PERMISSIONS:
                return applied(draft, "repoPermissions");
            case STEP_EXECUTOR_RUNTIME:
                return applied(draft, "executorRuntime");
            case STEP_FINAL_READINESS:
                return STAGE_READY.equals(stage)
                        ? new StatusPair(STATUS_COMPLETED, null) : new StatusPair(STATUS_PENDING, null);
            default:
                return variableStatus(step, template, draft, gaps);
        }
    }

    private StatusPair applied(JSONObject draft, String key) {
        JSONObject applied = draft.getJSONObject("configApplied");
        if (applied != null && Boolean.TRUE.equals(applied.getBoolean(key))) {
            return new StatusPair(STATUS_COMPLETED, null);
        }
        return new StatusPair(STATUS_PENDING, null);
    }

    private StatusPair variableStatus(OnboardingStepDO step, HarnessTemplate template, JSONObject draft,
            List<String> gaps) {
        String token = tokenForStep(template, step.getStepKey());
        if (token == null) {
            return new StatusPair(STATUS_BLOCKED, "模板中已不存在该变量");
        }
        JSONObject variables = draft.getJSONObject("variables");
        JSONObject entry = variables == null ? null : variables.getJSONObject(token);
        if (entry == null) {
            gaps.add(step.getName() + " 尚未填写");
            return new StatusPair(STATUS_PENDING, null);
        }
        if (Boolean.TRUE.equals(entry.getBoolean("notApplicable"))) {
            String reason = entry.getString("reason");
            return reason == null || reason.isBlank()
                    ? new StatusPair(STATUS_BLOCKED, "不适用必须说明理由")
                    : new StatusPair(STATUS_NOT_APPLICABLE, reason);
        }
        String value = entry.getString("value");
        if (value == null || value.isBlank()) {
            gaps.add(step.getName() + " 尚未填写");
            return new StatusPair(STATUS_PENDING, null);
        }
        if (value.contains("{{")) {
            gaps.add(step.getName() + " 仍包含未解析占位符");
            return new StatusPair(STATUS_BLOCKED, "取值仍包含未解析占位符");
        }
        return new StatusPair(STATUS_COMPLETED, null);
    }

    private String tokenForStep(HarnessTemplate template, String stepKey) {
        if (template == null) {
            return null;
        }
        for (HarnessVariable variable : template.getVariables()) {
            if (variableStepKey(variable.token()).equals(stepKey)) {
                return variable.token();
            }
        }
        return null;
    }

    public static String variableStepKey(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return "variable:" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private OnboardingStepDO toDO(WorkspaceOnboardingDO onboarding, GeneratedStep step, int order) {
        OnboardingStepDO entity = new OnboardingStepDO();
        entity.setTenantId(onboarding.getTenantId());
        entity.setOnboardingId(onboarding.getId());
        entity.setStepKey(step.stepKey());
        entity.setName(step.name());
        entity.setKind(step.kind());
        entity.setStepOrder(order);
        entity.setStatus(STATUS_PENDING);
        return entity;
    }

    private void validateDraftFields(Map<String, Object> draft) {
        for (String key : draft.keySet()) {
            if (!DRAFT_FIELDS.contains(key)) {
                throw BizException.keyed(ErrorCode.MCP_TOOL_ARGUMENT_INVALID,
                        "harness.onboarding.draft_field_invalid", new Object[]{key}, "草稿字段不受支持");
            }
        }
    }

    private JSONObject merge(JSONObject base, Map<String, Object> patch) {
        JSONObject merged = new JSONObject(base);
        patch.forEach((key, value) -> {
            if ("variables".equals(key) || "config".equals(key) || "evidence".equals(key)) {
                if (!(value instanceof Map<?, ?> incoming)) {
                    throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
                }
                Object existing = merged.get(key);
                JSONObject target = existing instanceof Map<?, ?> map ? new JSONObject(map) : new JSONObject();
                incoming.forEach((innerKey, innerValue) -> target.put(String.valueOf(innerKey),
                        "variables".equals(key) ? normalizeVariable(innerValue) : innerValue));
                merged.put(key, target);
            } else {
                merged.put(key, value);
            }
        });
        return merged;
    }

    /** 草稿接受与预检一致的文本值，同时保留显式不适用说明的对象格式。 */
    private JSONObject normalizeVariable(Object value) {
        if (value instanceof String text) {
            return new JSONObject(Map.of("value", text));
        }
        if (value instanceof Map<?, ?> fields
                && (!fields.containsKey("value") || fields.get("value") instanceof String)
                && (!fields.containsKey("notApplicable") || fields.get("notApplicable") instanceof Boolean)
                && (!fields.containsKey("reason") || fields.get("reason") instanceof String)) {
            return new JSONObject(fields);
        }
        throw BizException.keyed(ErrorCode.MCP_TOOL_ARGUMENT_INVALID,
                "harness.onboarding.variable_format_invalid", null, "引导草稿变量值格式无效");
    }

    private record GeneratedStep(String stepKey, String name, String kind) {
    }

    private record StatusPair(String status, String reason) {
    }

    static Long longArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException failure) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
    }

    static Integer intArg(Map<String, Object> args, String key) {
        Long value = longArg(args, key);
        return value == null ? null : value.intValue();
    }

    static String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return (Map<String, Object>) map;
    }
}
