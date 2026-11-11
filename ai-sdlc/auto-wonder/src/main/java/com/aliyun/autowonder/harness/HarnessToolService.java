package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置 MCP 的初始化能力适配层：只把参数转成领域调用，不在这里放业务规则，
 * 也不提供任意工具转发或直接写权限。
 */
@Service
public class HarnessToolService {

    private final HarnessTemplateCatalog catalog;
    private final WorkspaceOnboardingService onboardingService;
    private final HarnessPreviewService previewService;
    private final HarnessInitializationService initializationService;
    private final WorkspaceConfigurationService configurationService;
    private final WorkspaceReadinessService readinessService;

    public HarnessToolService(HarnessTemplateCatalog catalog, WorkspaceOnboardingService onboardingService,
            HarnessPreviewService previewService, HarnessInitializationService initializationService,
            WorkspaceConfigurationService configurationService, WorkspaceReadinessService readinessService) {
        this.catalog = catalog;
        this.onboardingService = onboardingService;
        this.previewService = previewService;
        this.initializationService = initializationService;
        this.configurationService = configurationService;
        this.readinessService = readinessService;
    }

    public List<Map<String, Object>> listTemplates() {
        List<Map<String, Object>> templates = new ArrayList<>();
        for (HarnessTemplate template : catalog.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("templateId", template.getKey());
            item.put("name", template.getName());
            item.put("description", template.getDescription());
            item.put("version", template.getVersion());
            item.put("sha256", template.getSha256());
            item.put("variableCount", template.getVariables().size());
            item.put("agentCount", template.getAgents().size());
            item.put("skillCount", template.getSkills().size());
            templates.add(item);
        }
        return templates;
    }

    /** 官方初始化 Skill 的多文件资源，供平台安装时打成包上传。 */
    public Map<String, String> onboardingSkillFiles() {
        return catalog.onboardingSkillFiles();
    }

    public Map<String, Object> getTemplate(Map<String, Object> args) {
        HarnessTemplate template = catalog.require(WorkspaceOnboardingService.stringArg(args, "templateId"),
                WorkspaceOnboardingService.stringArg(args, "version"),
                WorkspaceOnboardingService.stringArg(args, "sha256"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateId", template.getKey());
        result.put("name", template.getName());
        result.put("description", template.getDescription());
        result.put("version", template.getVersion());
        result.put("sha256", template.getSha256());
        result.put("schemaVersion", template.getSchemaVersion());
        result.put("squadName", template.getSquadName());
        result.put("variables", template.getVariables().stream().map(variable -> Map.of(
                "token", variable.token(), "label", variable.label(),
                "requiredWhen", variable.requiredWhen() == null ? "" : variable.requiredWhen())).toList());
        result.put("agents", template.getAgents().stream().map(agent -> Map.of(
                "roleCode", agent.roleCode(), "name", agent.name(),
                "hasSdlc", agent.sdlc() != null)).toList());
        result.put("skills", template.getSkills().stream().map(HarnessSkill::name).toList());
        result.put("agentCount", template.getAgents().size());
        result.put("skillCount", template.getSkills().size());
        result.put("sdlcCount", template.getAgents().stream().filter(agent -> agent.sdlc() != null).count());
        return result;
    }

    public Map<String, Object> prepare(long workspaceId, long userId, Map<String, Object> args) {
        WorkspaceOnboardingService.PrepareResult prepared = onboardingService.prepare(workspaceId, userId, args);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("onboarding", prepared.onboarding());
        result.put("created", prepared.created());
        Map<String, Object> draft = WorkspaceOnboardingService.mapArg(args, "draft");
        Object confirmCheckId = draft == null ? null : draft.get("confirmCheckId");
        if (confirmCheckId != null) {
            String checkId = String.valueOf(confirmCheckId);
            if (checkId.isBlank()) {
                throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
            }
            result.put("readiness", readinessService.confirm(workspaceId, userId, prepared.onboarding().id(), checkId));
            result.put("onboarding", onboardingService.view(
                    onboardingService.require(workspaceId, userId, prepared.onboarding().id())));
        }
        return result;
    }

    public Map<String, Object> getOnboarding(long workspaceId, long userId, Map<String, Object> args) {
        return onboardingService.get(workspaceId, userId, args);
    }

    public Map<String, Object> previewInitialization(long workspaceId, long userId, Map<String, Object> args) {
        return previewService.preview(workspaceId, userId, args);
    }

    public Map<String, Object> initialize(long workspaceId, long userId, Map<String, Object> args) {
        return initializationService.initialize(workspaceId, userId, args);
    }

    public Map<String, Object> getInitialization(long workspaceId, long userId, Map<String, Object> args) {
        return initializationService.get(workspaceId, userId, args);
    }

    public Map<String, Object> resumeInitialization(long workspaceId, long userId, Map<String, Object> args) {
        return initializationService.resume(workspaceId, userId, args);
    }

    public Map<String, Object> previewConfiguration(long workspaceId, long userId, Map<String, Object> args) {
        return configurationService.preview(workspaceId, userId, args);
    }

    public Map<String, Object> applyConfiguration(long workspaceId, long userId, Map<String, Object> args) {
        return configurationService.apply(workspaceId, userId, args);
    }

    public Map<String, Object> getConfiguration(long workspaceId, long userId, Map<String, Object> args) {
        return configurationService.get(workspaceId, userId, args);
    }

    public Map<String, Object> resumeConfiguration(long workspaceId, long userId, Map<String, Object> args) {
        return configurationService.resume(workspaceId, userId, args);
    }

    public Map<String, Object> checkReadiness(long workspaceId, long userId, Map<String, Object> args) {
        return readinessService.check(workspaceId, userId, args);
    }
}
