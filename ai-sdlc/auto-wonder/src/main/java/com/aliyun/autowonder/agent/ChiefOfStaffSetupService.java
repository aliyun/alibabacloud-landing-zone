package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.conversation.ConversationProtocolFeatures;
import com.aliyun.autowonder.conversation.ConversationRuntimePresence;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.ExecutorService;
import com.aliyun.autowonder.executor.dto.CreateExecutorRequest;
import com.aliyun.autowonder.mcp.PlatformSkillCatalog;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;

/** Deterministic bootstrap: no model calls, no replacing custom configuration or pending drafts. */
@Service
public class ChiefOfStaffSetupService {
    private final AgentDao agents;
    private final AgentSkillDao bindings;
    private final AgentEnvironmentVariableRefDao environmentRefs;
    private final SkillDao skills;
    private final PlatformSkillCatalog catalog;
    private final ChiefEffectiveConfigResolver chiefConfig;
    private final PlatformAgentSeeder seeder;
    private final ExecutorDao executors;
    private final ExecutorService executorService;
    private final ConversationRuntimePresence presence;
    private final WorkspaceDao workspaces;
    private final com.aliyun.autowonder.executor.ExecutorLaunchConfigService launchConfigs;
    private final com.aliyun.autowonder.executor.ExecutorLaunchOptionsService launchOptions;
    public ChiefOfStaffSetupService(AgentDao agents, AgentSkillDao bindings, SkillDao skills,
            PlatformSkillCatalog catalog,
            PlatformAgentSeeder seeder, ExecutorDao executors, ExecutorService executorService,
            ConversationRuntimePresence presence, WorkspaceDao workspaces,
            com.aliyun.autowonder.executor.ExecutorLaunchConfigService launchConfigs,
            com.aliyun.autowonder.executor.ExecutorLaunchOptionsService launchOptions, AgentEnvironmentVariableRefDao environmentRefs) {
        this.environmentRefs=environmentRefs; this.agents=agents; this.bindings=bindings; this.skills=skills; this.catalog=catalog;
        this.chiefConfig=new ChiefEffectiveConfigResolver(catalog); this.seeder=seeder;
        this.executors=executors; this.executorService=executorService; this.presence=presence; this.workspaces=workspaces; this.launchConfigs=launchConfigs; this.launchOptions=launchOptions;
    }
    public record Status(Long agentId, Long executorId, boolean agentConfigured, boolean skillsConfigured,
            boolean executorConfigured, boolean launchConfigured, boolean ready, List<String> missingSkills) {}

    public Status inspect(long workspaceId) {
        var agent = agents.findPlatformAgent(workspaceId);
        if (agent == null || agent.getOnlineVersionId() == null)
            return new Status(agent == null ? null : agent.getId(), null, false, false, false, false, false,
                    catalog.list().stream().map(s -> s.getId()).toList());
        var attached = bindings.listByVersion(agent.getOnlineVersionId());
        List<String> missing = new ArrayList<>();
        var capabilities = com.aliyun.autowonder.dispatch.PackageContextAssembler.buildCapabilities(
                bindings, skills, workspaceId, agent.getOnlineVersionId());
        for (var binding : attached) {
            if (capabilities.stream().noneMatch(c -> java.util.Objects.equals(c.get("id"), binding.getSkillId()))) {
                missing.add("skill:" + binding.getSkillId());
            }
        }
        try { chiefConfig.resolve(capabilities); }
        catch (IllegalArgumentException conflict) { missing.add("workspace-capabilities"); }
        var registered = executors.listByAgent(workspaceId, agent.getId());
        boolean requiresEnvironment = !environmentRefs.listByVersion(workspaceId,agent.getOnlineVersionId()).isEmpty();
        var online = registered.stream().filter(e -> completeLaunch(e)
                && presence.isExecutorOnline(e.getId())
                && presence.supportsProtocolFeature(e.getId(), ConversationProtocolFeatures.TURN_EVENT)
                && (!requiresEnvironment || presence.supportsProtocolFeature(e.getId(),
                    com.aliyun.autowonder.dispatch.ExecutorProtocolFeatures.AGENT_ENVIRONMENT_VARIABLES_V1))).findFirst();
        Long executorId = online.map(e -> e.getId()).orElse(registered.stream().filter(this::completeLaunch).findFirst().map(e -> e.getId()).orElse(registered.isEmpty() ? null : registered.get(0).getId()));
        boolean launchConfigured=registered.stream().anyMatch(this::completeLaunch);
        return new Status(agent.getId(), executorId, true, missing.isEmpty(), !registered.isEmpty(), launchConfigured,
                missing.isEmpty() && online.isPresent(), List.copyOf(missing));
    }

    private boolean completeLaunch(com.aliyun.autowonder.executor.ExecutorDO executor) {
        if (com.aliyun.autowonder.executor.ExecutorLaunchOptionsService.canonicalClientKind(executor.getClientKind()) == null) return false;
        try {
            var config=launchConfigs.readStoredConfig(executor.getId(),executor.getTenantId());
            if (config.getMemoryMode()==null || config.getMemoryMode().isBlank()) return false;
            return !com.aliyun.autowonder.executor.ExecutorLaunchOptionsService.isQoderFamily(
                    com.aliyun.autowonder.executor.ExecutorLaunchOptionsService.resolveProvider(executor.getClientKind()))
                    || (config.getModel()!=null && !config.getModel().isBlank()
                    && config.getReasoningEffort()!=null && !config.getReasoningEffort().isBlank()
                    && config.getContextWindow()!=null && !config.getContextWindow().isBlank());
        } catch (BizException invalid) { return false; }
    }
    @Transactional
    public Status complete(long workspaceId, long userId, CreateExecutorRequest executor) {
        if (workspaces.findByIdForUpdate(workspaceId) == null) throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        seeder.seed(workspaceId, userId);
        var agent = agents.findPlatformAgent(workspaceId);
        Status before = inspect(workspaceId);
        if (!before.agentConfigured()) throw new BizException(ErrorCode.PLATFORM_CONVERSATION_NOT_READY);
        if (!before.executorConfigured()) {
            executor.setName(PlatformAgentSeeder.PLATFORM_AGENT_NAME);
            executorService.create(agent.getId(), executor, workspaceId, userId);
        }
        if (before.executorConfigured() && !before.launchConfigured()) {
            var target=executors.listByAgent(workspaceId,agent.getId()).get(0);
            var stored=launchConfigs.readStoredConfig(target.getId(),workspaceId);
            String kind=target.getClientKind()==null || target.getClientKind().isBlank()?executor.getClientKind():target.getClientKind();
            var defaults=launchOptions.launchOptions(kind);
            var update=new com.aliyun.autowonder.executor.dto.UpdateExecutorLaunchConfigRequest();
            update.setClientKind(kind); update.setVersion(stored.getVersion());
            update.setMemoryMode(presentOr(stored.getMemoryMode(),defaults.getDefaultMemoryMode()));
            update.setModel(presentOr(stored.getModel(),defaults.getDefaultCreateModel()));
            update.setReasoningEffort(presentOr(stored.getReasoningEffort(),defaults.getDefaultReasoningEffort()));
            update.setContextWindow(presentOr(stored.getContextWindow(),defaults.getDefaultContextWindow()));
            update.setMaxConcurrentDispatches(stored.getMaxConcurrentDispatches());
            update.setModelUpgradePolicy(stored.getModelUpgradePolicy());
            launchConfigs.updateConfig(target.getId(),workspaceId,update,userId);
        }
        return inspect(workspaceId);
    }
    private static String presentOr(String value,String fallback) {return value==null || value.isBlank()?fallback:value;}
}
