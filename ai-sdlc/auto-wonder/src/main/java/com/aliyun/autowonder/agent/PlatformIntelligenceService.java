package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.conversation.*;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Workspace-scoped, asynchronous access to the platform employee's configured capabilities. */
@Service
public class PlatformIntelligenceService {
    private final AgentDao agentDao;
    private final AgentVersionDao versionDao;
    private final ExecutorSelector executorSelector;
    private final AgentConversationService conversations;
    private final AgentConversationDao conversationDao;
    private final AgentConversationTurnDao turnDao;

    public PlatformIntelligenceService(AgentDao agentDao, AgentVersionDao versionDao,
            ExecutorSelector executorSelector, AgentConversationService conversations,
            AgentConversationDao conversationDao, AgentConversationTurnDao turnDao) {
        this.agentDao = agentDao;
        this.versionDao = versionDao;
        this.executorSelector = executorSelector;
        this.conversations = conversations;
        this.conversationDao = conversationDao;
        this.turnDao = turnDao;
    }

    public enum Availability { AVAILABLE, NOT_CONFIGURED, AGENT_OFFLINE, VERSION_UNAVAILABLE, RUNTIME_UNAVAILABLE }

    public record CapabilityStatus(Long agentId, Availability status) {
        public boolean isAvailable() { return status == Availability.AVAILABLE; }
    }

    /** status follows conversation turn states: QUEUED/PROCESSING/SUCCESS/FAILED/CANCELED. */
    public record InvocationResult(UUID requestId, Long conversationId, Long turnId,
            String status, String content, String error) {
        public boolean isTerminal() {
            return List.of("SUCCESS", "FAILED", "CANCELED").contains(status);
        }
    }

    public static class UnavailableException extends IllegalStateException {
        private final CapabilityStatus capabilityStatus;

        public UnavailableException(CapabilityStatus capabilityStatus) {
            super("Platform intelligence unavailable: " + capabilityStatus.status());
            this.capabilityStatus = capabilityStatus;
        }

        public CapabilityStatus getCapabilityStatus() { return capabilityStatus; }
    }

    /** A read-only snapshot, not a guarantee or reservation for a subsequent invocation. */
    public CapabilityStatus getStatus(Long workspaceId) {
        requireWorkspace(workspaceId);
        AgentDO agent = agentDao.findPlatformAgent(workspaceId);
        if (agent == null || !workspaceId.equals(agent.getTenantId())
                || !"PLATFORM".equals(agent.getKind()) || Integer.valueOf(1).equals(agent.getIsDeleted())) {
            return new CapabilityStatus(null, Availability.NOT_CONFIGURED);
        }
        if (!"ONLINE".equals(agent.getStatus())) {
            return new CapabilityStatus(agent.getId(), Availability.AGENT_OFFLINE);
        }
        AgentVersionDO version = agent.getOnlineVersionId() == null ? null
                : versionDao.findById(agent.getOnlineVersionId());
        if (version == null || !agent.getId().equals(version.getAgentId())
                || !workspaceId.equals(version.getTenantId()) || !hasIdentity(version)) {
            return new CapabilityStatus(agent.getId(), Availability.VERSION_UNAVAILABLE);
        }
        return new CapabilityStatus(agent.getId(), executorSelector.hasAvailableExecutor(agent.getId())
                ? Availability.AVAILABLE : Availability.RUNTIME_UNAVAILABLE);
    }

    /** One isolated conversation per request UUID; retry the same UUID with the same prompt. */
    @Transactional
    public InvocationResult invoke(Long workspaceId, UUID requestId, String prompt) {
        requireWorkspace(workspaceId);
        requireRequest(requestId);
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        AgentConversationTurnDO existing = turnDao.findByExternalMsgId(workspaceId, externalId(requestId));
        if (existing != null) {
            if (!prompt.equals(existing.getContent())) {
                throw new IllegalArgumentException("requestId already used with a different prompt");
            }
            return getResult(workspaceId, requestId);
        }
        CapabilityStatus status = getStatus(workspaceId);
        if (!status.isAvailable()) {
            throw new UnavailableException(status);
        }
        conversations.submitTurn(workspaceId, status.agentId(), PlatformIntelligenceChannelSink.CHANNEL,
                requestId.toString(), prompt, externalId(requestId));
        return getResult(workspaceId, requestId);
    }

    /** Returns null if this workspace has no such invocation. Results survive application restarts. */
    @Transactional(readOnly = true)
    public InvocationResult getResult(Long workspaceId, UUID requestId) {
        requireWorkspace(workspaceId);
        requireRequest(requestId);
        AgentConversationTurnDO inbound = turnDao.findByExternalMsgId(workspaceId, externalId(requestId));
        if (inbound == null) {
            return null;
        }
        AgentConversationDO conversation = conversationDao.findById(workspaceId, inbound.getConversationId());
        if (conversation == null || !PlatformIntelligenceChannelSink.CHANNEL.equals(conversation.getChannel())
                || !requestId.toString().equals(conversation.getChannelConversationId())) {
            throw new IllegalArgumentException("request does not belong to platform intelligence");
        }
        List<AgentConversationTurnDO> turns = turnDao.listTurnsByConversation(workspaceId, conversation.getId());
        AgentConversationTurnDO outbound = turns.stream().filter(t -> "OUT".equals(t.getDirection()))
                .findFirst().orElse(null);
        // Read the status from the same snapshot as the response, including a just-completed turn.
        AgentConversationTurnDO current = turns.stream().filter(t -> inbound.getId().equals(t.getId()))
                .findFirst().orElse(inbound);
        return new InvocationResult(requestId, conversation.getId(), inbound.getId(),
                outbound == null ? current.getStatus() : outbound.getStatus(),
                outbound == null ? null : outbound.getContent(),
                outbound == null ? current.getError() : outbound.getError());
    }

    private static boolean hasIdentity(AgentVersionDO v) {
        return hasText(v.getRoleName()) || hasText(v.getRoleCode()) || hasText(v.getBusinessBackground())
                || hasText(v.getResponsibilities()) || hasText(v.getIdentityJson());
    }

    private static boolean hasText(String value) { return value != null && !value.isEmpty(); }
    private static String externalId(UUID requestId) { return "platform-internal:" + requestId; }
    private static void requireWorkspace(Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
    }
    private static void requireRequest(UUID requestId) {
        if (requestId == null) { throw new IllegalArgumentException("requestId is required"); }
    }
}
