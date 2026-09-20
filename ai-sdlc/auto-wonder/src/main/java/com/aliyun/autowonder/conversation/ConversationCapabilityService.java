package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentSkillDao;
import com.aliyun.autowonder.dispatch.PackageContextAssembler;
import com.aliyun.autowonder.mcp.ConversationMcpTokenService;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.taskpackage.TaskPackageResult;
import com.aliyun.autowonder.taskpackage.TaskPackager;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
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

    public ConversationCapabilityService(AgentSkillDao bindingDao, SkillDao skillDao,
            AgentDao agentDao, PackageContextAssembler contextAssembler,
            TaskPackager packager, ConversationMcpTokenService tokenService, SecretCrypto secretCrypto) {
        this.bindingDao = bindingDao;
        this.skillDao = skillDao;
        this.agentDao = agentDao;
        this.contextAssembler = contextAssembler;
        this.packager = packager;
        this.tokenService = tokenService;
        this.secretCrypto = secretCrypto;
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
        long principalId = resolvePrincipal(conversation, agent);
        var repos = contextAssembler.buildRepos(conversation.getTenantId(), conversation.getAgentVersionId(), agent);
        TaskPackageResult bundle = packager.buildConversationCapabilities(
                conversation.getTenantId(), conversation.getId(), turnId,
                conversation.getAgentId(), conversation.getAgentVersionId(),
                PackageContextAssembler.buildCapabilities(bindingDao, skillDao,
                        conversation.getTenantId(), conversation.getAgentVersionId()),
                repos, contextAssembler.buildRepoMap(conversation.getTenantId(), repos));
        String token = tokenService.issue(conversation, principalId);
        return new ConversationCapabilitySnapshot(conversation.getAgentVersionId(),
                bundle.getDownloadUrl(), bundle.getSha256(), bundle.getContentHash(), token,
                resolveMcpSecrets(bundle.getMcpSecretRefs()));
    }

    /**
     * 平台管家会话的 MCP 身份只能是会话 Owner 本人。此前这里回退到 Agent 的创建者/修改者，
     * 结果是所有人共用最后一个改过 Chief 的人的身份，写操作与审计全部记在他名下。
     * Owner 缺失时宁可整轮失败，也不能退回旧行为；钉钉与工单澄清渠道保持原样。
     */
    private long resolvePrincipal(AgentConversationDO conversation, AgentDO agent) {
        if (PlatformConversationChannel.CHANNEL.equals(conversation.getChannel())) {
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
