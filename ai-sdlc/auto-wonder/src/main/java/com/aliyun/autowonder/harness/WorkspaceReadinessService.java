package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.agent.AgentRepoPermDao;
import com.aliyun.autowonder.agent.AgentRepoPermDO;
import com.aliyun.autowonder.agent.AgentService;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.ExecutorService;
import com.aliyun.autowonder.executor.dto.ExecutorVO;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.skill.SkillService;
import com.aliyun.autowonder.skill.dto.SkillVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读就绪检查：必要变量、Skill 绑定、仓库权限、全部默认执行器在线及有效访问证据。
 * 未知不能算通过；检查本身不改 READY，客户确认后才由服务端复核并写入。
 */
@Service
public class WorkspaceReadinessService {

    public static final String PASS = "PASS";
    public static final String BLOCKED = "BLOCKED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";

    private final WorkspaceOnboardingDao dao;
    private final WorkspaceOnboardingService onboardingService;
    private final HarnessTemplateCatalog catalog;
    private final ExecutorService executorService;
    private final AgentService agentService;
    private final AgentRepoPermDao repoPermDao;
    private final SkillService skillService;

    public WorkspaceReadinessService(WorkspaceOnboardingDao dao, WorkspaceOnboardingService onboardingService,
            HarnessTemplateCatalog catalog, ExecutorService executorService, AgentService agentService,
            AgentRepoPermDao repoPermDao, SkillService skillService) {
        this.dao = dao;
        this.onboardingService = onboardingService;
        this.catalog = catalog;
        this.executorService = executorService;
        this.agentService = agentService;
        this.repoPermDao = repoPermDao;
        this.skillService = skillService;
    }

    /**
     * 纯只读检查：不写表、不推进阶段、不标记步骤。checkId 由 onboarding、配置版本与结论确定，
     * 客户确认时服务端重新计算并复核当前在线状态。
     */
    public Map<String, Object> check(long workspaceId, long userId, Map<String, Object> args) {
        Long onboardingId = WorkspaceOnboardingService.longArg(args, "onboardingId");
        if (onboardingId == null) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        WorkspaceOnboardingDO onboarding = onboardingService.require(workspaceId, userId, onboardingId);
        Evaluation evaluation = evaluate(onboarding);
        int configVersion = onboarding.getConfigVersion() == null ? 0 : onboarding.getConfigVersion();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkId", checkId(onboarding, evaluation.status()));
        result.put("onboardingId", String.valueOf(onboarding.getId()));
        result.put("configVersion", configVersion);
        result.put("checkedAt", new Date().toInstant().toString());
        result.put("status", evaluation.status());
        result.put("checks", evaluation.checks());
        result.put("gaps", evaluation.gaps());
        return result;
    }

    /** 客户确认检查结果后由服务端复核版本与在线状态，再计算 READY。 */
    public Map<String, Object> confirm(long workspaceId, long userId, long onboardingId, String checkId) {
        WorkspaceOnboardingDO onboarding = onboardingService.require(workspaceId, userId, onboardingId);
        int configVersion = onboarding.getConfigVersion() == null ? 0 : onboarding.getConfigVersion();
        Evaluation evaluation = evaluate(onboarding);
        if (checkId == null || !checkId.equals(checkId(onboarding, evaluation.status()))) {
            throw BizException.keyed(ErrorCode.HARNESS_ONBOARDING_VERSION_CONFLICT,
                    "harness.readiness.check_mismatch", null, "就绪检查结果已变化，请重新检查");
        }
        if (!PASS.equals(evaluation.status())) {
            throw BizException.keyed(ErrorCode.HARNESS_READINESS_BLOCKED, "harness.readiness.blocked",
                    null, "就绪检查未通过");
        }
        WorkspaceReadinessCheckDO record = new WorkspaceReadinessCheckDO();
        record.setTenantId(workspaceId);
        record.setWorkspaceId(workspaceId);
        record.setOnboardingId(onboarding.getId());
        record.setConfigVersion(configVersion);
        record.setStatus(evaluation.status());
        record.setEvidenceJson(JSON.toJSONString(evaluation.checks()));
        record.setCreatorId(userId);
        record.setCheckedAt(new Date());
        dao.insertCheck(record);
        onboardingService.recordAppliedConfig(onboarding, userId, java.util.Set.of("executorRuntime"), null);
        onboardingService.markReady(onboarding, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("onboardingId", String.valueOf(onboarding.getId()));
        result.put("stage", onboarding.getStage());
        result.put("configVersion", configVersion);
        result.put("checkId", checkId);
        result.put("checks", evaluation.checks());
        return result;
    }

    static String checkId(WorkspaceOnboardingDO onboarding, String status) {
        int configVersion = onboarding.getConfigVersion() == null ? 0 : onboarding.getConfigVersion();
        return HarnessPreviewService.sha256(onboarding.getId() + ":" + configVersion + ":" + status);
    }

    public Evaluation evaluate(WorkspaceOnboardingDO onboarding) {
        List<Map<String, Object>> checks = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        HarnessTemplate template = onboardingService.selectedTemplate(onboarding);
        if (template == null) {
            checks.add(check("template", BLOCKED, "模板尚未选择"));
            return new Evaluation(BLOCKED, checks, List.of("模板尚未选择"));
        }
        JSONObject draft = onboardingService.draft(onboarding);

        boolean initialized = !WorkspaceOnboardingService.STAGE_DRAFT.equals(onboarding.getStage())
                && !WorkspaceOnboardingService.STAGE_INITIALIZING.equals(onboarding.getStage());
        checks.add(check("initialization", initialized ? PASS : BLOCKED,
                initialized ? "模板已初始化" : "模板尚未初始化完成"));

        int unresolved = 0;
        JSONObject variables = draft.getJSONObject("variables");
        for (HarnessVariable variable : template.getVariables()) {
            JSONObject entry = variables == null ? null : variables.getJSONObject(variable.token());
            if (entry == null) {
                unresolved++;
                gaps.add(variable.label() + " 尚未填写");
                continue;
            }
            if (Boolean.TRUE.equals(entry.getBoolean("notApplicable"))) {
                String reason = entry.getString("reason");
                if (reason == null || reason.isBlank()) {
                    unresolved++;
                    gaps.add(variable.label() + " 的不适用缺少理由");
                }
                continue;
            }
            String value = entry.getString("value");
            if (value == null || value.isBlank() || value.contains("{{")) {
                unresolved++;
                gaps.add(variable.label() + " 尚未填写或仍有占位符");
            }
        }
        checks.add(check("variables", unresolved == 0 ? PASS : BLOCKED,
                unresolved == 0 ? "必要变量均已填写或确认不适用" : unresolved + " 个变量未完成"));

        JSONObject objects = draft.getJSONObject("objects");
        int missingSkills = 0;
        List<String> workspaceSkills = skillService.list(onboarding.getTenantId(), "SKILL", 1, 100).stream()
                .map(SkillVO::getName).toList();
        for (HarnessSkill skill : template.getSkills()) {
            if (!workspaceSkills.contains(skill.name())) {
                missingSkills++;
                gaps.add("Skill " + skill.name() + " 不在当前空间");
            }
        }
        checks.add(check("skills", missingSkills == 0 ? PASS : BLOCKED,
                missingSkills == 0 ? "模板 Skill 均已导入" : missingSkills + " 个 Skill 缺失"));

        Map<String, Object> repositories = draft.getJSONObject("repositories") == null
                ? Map.of() : new LinkedHashMap<>(draft.getJSONObject("repositories"));
        int missingPermissions = 0;
        if (repositories.isEmpty()) {
            missingPermissions = -1;
            gaps.add("尚未登记项目仓库");
        } else {
            for (Map.Entry<String, Object> repo : repositories.entrySet()) {
                Long repoId = repo.getValue() instanceof Number number ? number.longValue() : null;
                if (repoId == null || repoId <= 0) {
                    continue;
                }
                for (HarnessAgent agent : template.getAgents()) {
                    String expected = catalog.repoPermissionFor(agent.roleCode());
                    if (expected == null) {
                        continue;
                    }
                    Long agentId = objectId(objects, "agents", agent.roleCode());
                    if (agentId == null) {
                        missingPermissions++;
                        gaps.add("数字人 " + agent.name() + " 尚未创建");
                        continue;
                    }
                    AgentVO agentVO = agentService.get(agentId, onboarding.getTenantId());
                    if (agentVO.getOnlineVersionId() == null) {
                        missingPermissions++;
                        gaps.add(agent.name() + " 尚未发布在线版本");
                        continue;
                    }
                    AgentRepoPermDO perm = repoPermDao.listByVersion(agentVO.getOnlineVersionId()).stream()
                            .filter(item -> repoId.equals(item.getRepoId()))
                            .findFirst().orElse(null);
                    if (perm == null || perm.getPermLevel() == null
                            || !perm.getPermLevel().equalsIgnoreCase(expected)) {
                        missingPermissions++;
                        gaps.add(repo.getKey() + " 缺少 " + agent.roleCode() + " 的 " + expected + " 权限");
                    }
                }
            }
        }
        checks.add(check("repoPermissions", missingPermissions == 0 ? PASS : BLOCKED,
                missingPermissions == 0 ? "仓库权限已生效" : "仓库权限未完整生效"));

        int offline = 0;
        for (HarnessAgent agent : template.getAgents()) {
            Long agentId = objectId(objects, "agents", agent.roleCode());
            if (agentId == null) {
                offline++;
                gaps.add("数字人 " + agent.name() + " 尚未创建");
                continue;
            }
            String executorName = agent.name() + catalog.manifest().executorDefaults().nameSuffix();
            ExecutorVO executor = executorService.listByAgent(agentId, onboarding.getTenantId()).stream()
                    .filter(item -> executorName.equals(item.getName()))
                    .findFirst().orElse(null);
            if (executor == null || !"ONLINE".equals(executor.getStatus())) {
                offline++;
                gaps.add("数字人 " + agent.name() + " 的默认执行器未在线");
            }
        }
        checks.add(check("executors", offline == 0 ? PASS : BLOCKED,
                offline == 0 ? "全部默认执行器在线" : offline + " 个默认执行器未在线"));

        JSONObject evidence = draft.getJSONObject("evidence");
        boolean gitAccess = evidence != null && truthy(evidence.get("gitAccess"));
        boolean runtimeEnvironment = evidence != null && truthy(evidence.get("runtimeEnvironment"));
        boolean evidencePresent = gitAccess && runtimeEnvironment;
        if (!gitAccess) {
            gaps.add("缺少 Git 只读访问证据");
        }
        if (!runtimeEnvironment) {
            gaps.add("缺少执行环境就绪证据");
        }
        checks.add(check("accessEvidence", evidencePresent ? PASS : UNKNOWN,
                evidencePresent ? "访问与运行环境证据有效" : "访问或运行环境证据未知"));

        boolean allPass = checks.stream().allMatch(item -> PASS.equals(item.get("status"))
                || NOT_APPLICABLE.equals(item.get("status")));
        boolean anyBlocked = checks.stream().anyMatch(item -> BLOCKED.equals(item.get("status")));
        String status = allPass ? PASS : (anyBlocked ? BLOCKED : UNKNOWN);
        return new Evaluation(status, checks, gaps);
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Map<?, ?> map) {
            return Boolean.TRUE.equals(map.get("valid"));
        }
        return false;
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

    private Map<String, Object> check(String id, String status, String detail) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("id", id);
        check.put("status", status);
        check.put("detail", detail);
        check.put("checkedAt", new Date().toInstant().toString());
        return check;
    }

    public record Evaluation(String status, List<Map<String, Object>> checks, List<String> gaps) {
    }
}
