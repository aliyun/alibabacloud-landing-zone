package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentSkillDao;
import com.aliyun.autowonder.agent.ChiefEffectiveConfigResolver;
import com.aliyun.autowonder.agent.PlatformAgentSeeder;
import com.aliyun.autowonder.dispatch.PackageContextAssembler;
import com.aliyun.autowonder.mcp.ConversationMcpTokenService;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.squad.workflow.SquadWorkflowChannelSink;
import com.aliyun.autowonder.taskpackage.TaskPackageResult;
import com.aliyun.autowonder.taskpackage.TaskPackager;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConversationCapabilityService {
    private final AgentSkillDao bindingDao;
    private final SkillDao skillDao;
    private final AgentDao agentDao;
    private final PackageContextAssembler contextAssembler;
    private final TaskPackager packager;
    private final ConversationMcpTokenService tokenService;
    private final SecretCrypto secretCrypto;
    private final AgentConversationTurnDao turnDao;
    private final ChiefEffectiveConfigResolver chiefConfig;

    public ConversationCapabilityService(AgentSkillDao bindingDao, SkillDao skillDao,
            AgentDao agentDao, PackageContextAssembler contextAssembler,
            TaskPackager packager, ConversationMcpTokenService tokenService, SecretCrypto secretCrypto) {
        this(bindingDao, skillDao, agentDao, contextAssembler, packager, tokenService, secretCrypto,
                null, new ChiefEffectiveConfigResolver(new com.aliyun.autowonder.mcp.PlatformSkillCatalog()));
    }

    public ConversationCapabilityService(AgentSkillDao bindingDao, SkillDao skillDao,
            AgentDao agentDao, PackageContextAssembler contextAssembler, TaskPackager packager,
            ConversationMcpTokenService tokenService, SecretCrypto secretCrypto, AgentConversationTurnDao turnDao) {
        this(bindingDao, skillDao, agentDao, contextAssembler, packager, tokenService, secretCrypto,
                turnDao, new ChiefEffectiveConfigResolver(new com.aliyun.autowonder.mcp.PlatformSkillCatalog()));
    }

    public ConversationCapabilityService(AgentSkillDao bindingDao, SkillDao skillDao,
            AgentDao agentDao, PackageContextAssembler contextAssembler, TaskPackager packager,
            ConversationMcpTokenService tokenService, SecretCrypto secretCrypto, ChiefEffectiveConfigResolver chiefConfig) {
        this(bindingDao, skillDao, agentDao, contextAssembler, packager, tokenService, secretCrypto, null, chiefConfig);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ConversationCapabilityService(AgentSkillDao bindingDao, SkillDao skillDao,
            AgentDao agentDao, PackageContextAssembler contextAssembler, TaskPackager packager,
            ConversationMcpTokenService tokenService, SecretCrypto secretCrypto,
            AgentConversationTurnDao turnDao, ChiefEffectiveConfigResolver chiefConfig) {
        this.turnDao = turnDao;
        this.bindingDao = bindingDao;
        this.skillDao = skillDao;
        this.agentDao = agentDao;
        this.contextAssembler = contextAssembler;
        this.packager = packager;
        this.tokenService = tokenService;
        this.secretCrypto = secretCrypto;
        this.chiefConfig = chiefConfig;
    }

    public ConversationCapabilitySnapshot prepare(AgentConversationDO conversation, Long turnId) {
        if (conversation == null || conversation.getTenantId() == null || conversation.getId() == null
                || conversation.getAgentId() == null || conversation.getAgentVersionId() == null
                || turnId == null) {
            throw new IllegalArgumentException("conversation capability identity is incomplete");
        }
        AgentDO agent = agentDao.findById(conversation.getAgentId());
        if (agent == null || !conversation.getTenantId().equals(agent.getTenantId())) {
            throw new IllegalStateException("conversation agent is unavailable");
        }
        boolean web = PlatformConversationChannel.CHANNEL.equals(conversation.getChannel())
                || "WORKITEM_CLARIFICATION".equals(conversation.getChannel());
        AgentConversationTurnDO turn = web && turnDao != null
                ? turnDao.findByConversationTurn(conversation.getTenantId(), conversation.getId(), turnId) : null;
        boolean actorScoped = web && turn != null && turn.getSubmittedUserId() != null;
        if ("WORKITEM_CLARIFICATION".equals(conversation.getChannel()) && !actorScoped) {
            throw com.aliyun.autowonder.common.error.BizException.keyed(
                    com.aliyun.autowonder.common.error.ErrorCode.NO_PERMISSION,
                    "conversation.actor_unknown", null, "conversation turn sender is unavailable; send a new message");
        }
        long principalId = actorScoped ? turn.getSubmittedUserId() : resolvePrincipal(conversation, agent);
        // 小队工作流只做一次只读配置分析与图谱提交：无仓库、无技能，避免 Chief 拿会话发起人的
        // 全量能力包去调用无关写工具。
        boolean restricted = SquadWorkflowChannelSink.CHANNEL.equals(conversation.getChannel());
        boolean officialChief = !restricted && PlatformAgentSeeder.PLATFORM_KIND.equals(agent.getKind());
        List<Map<String, Object>> capabilities = restricted ? List.of()
                : PackageContextAssembler.buildCapabilities(bindingDao, skillDao,
                        conversation.getTenantId(), conversation.getAgentVersionId(), officialChief);
        ChiefEffectiveConfigResolver.EffectiveConfig official =
                officialChief ? chiefConfig.resolve(capabilities) : null;
        if (official != null) {
            capabilities = official.capabilities();
        }
        var repos = restricted ? List.<Map<String, Object>>of()
                : contextAssembler.buildRepos(conversation.getTenantId(),
                        conversation.getAgentVersionId(), agent);
        TaskPackageResult bundle = packager.buildConversationCapabilities(
                conversation.getTenantId(), conversation.getId(), turnId,
                conversation.getAgentId(), conversation.getAgentVersionId(),
                capabilities, repos,
                restricted ? null : contextAssembler.buildRepoMap(conversation.getTenantId(), repos));
        String token = actorScoped ? tokenService.issueForTurn(conversation, turnId)
                : tokenService.issue(conversation, principalId);
        return new ConversationCapabilitySnapshot(conversation.getAgentVersionId(),
                bundle.getDownloadUrl(), bundle.getSha256(), bundle.getContentHash(), token,
                resolveMcpSecrets(bundle.getMcpSecretRefs()), actorScoped ? 1 : null,
                official == null ? null : official.systemPrompt());
    }

    /**
     * 平台管家会话的 MCP 身份只能是会话 Owner 本人。此前这里回退到 Agent 的创建者/修改者，
     * 结果是所有人共用最后一个改过 Chief 的人的身份，写操作与审计全部记在他名下。
     * Owner 缺失时宁可整轮失败，也不能退回旧行为；IM 渠道保留旧规则，网页澄清必须走持久化回合。
     */
    private long resolvePrincipal(AgentConversationDO conversation, AgentDO agent) {
        if (PlatformConversationChannel.CHANNEL.equals(conversation.getChannel())
                || SquadWorkflowChannelSink.CHANNEL.equals(conversation.getChannel())) {
            Long owner = conversation.getOwnerUserId();
            if (owner == null || owner <= 0) {
                throw new IllegalStateException("platform conversation owner is unavailable");
            }
            return owner;
        }
        long principalId = agent.getCreatorId() == null ? 0L : agent.getCreatorId();
        if (principalId <= 0 && agent.getModifierId() != null) {
            principalId = agent.getModifierId();
        }
        if (principalId <= 0) {
            throw new IllegalStateException("conversation MCP principal is unavailable");
        }
        return principalId;
    }

    private Map<String, String> resolveMcpSecrets(Map<String, String> refs) {
        if (refs == null || refs.isEmpty()) {
            return Map.of();
        }
        if (secretCrypto == null) {
            throw new IllegalStateException("MCP 私密配置需要密文存储支持");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String ref : refs.keySet()) {
            values.put(ref, secretCrypto.decrypt(ref));
        }
        return values;
    }
}
