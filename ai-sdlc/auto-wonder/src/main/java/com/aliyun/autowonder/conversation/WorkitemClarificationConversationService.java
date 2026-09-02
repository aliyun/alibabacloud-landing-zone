package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.conversation.dto.ClarificationConversationVO;
import com.aliyun.autowonder.conversation.dto.ClarificationTurnVO;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class WorkitemClarificationConversationService {

    private static final Logger log = LoggerFactory.getLogger(WorkitemClarificationConversationService.class);
    private static final String CHANNEL = "WORKITEM_CLARIFICATION";
    private static final String BIZ_REF_TYPE = "WORKITEM";

    private final AgentConversationDao convDao;
    private final AgentConversationTurnDao turnDao;
    private final AgentConversationService conversationService;
    private final AgentDao agentDao;
    private final ExecutorSelector executorSelector;
    private final ConversationRuntimePresence runtimePresence;

    public WorkitemClarificationConversationService(AgentConversationDao convDao,
            AgentConversationTurnDao turnDao, AgentConversationService conversationService,
            AgentDao agentDao, ExecutorSelector executorSelector,
            ConversationRuntimePresence runtimePresence) {
        this.convDao = convDao;
        this.turnDao = turnDao;
        this.conversationService = conversationService;
        this.agentDao = agentDao;
        this.executorSelector = executorSelector;
        this.runtimePresence = runtimePresence;
    }

    public void verifyConversationBelongsToWorkitem(Long tenantId, Long workitemId, Long conversationId) {
        AgentConversationDO conv = convDao.findById(tenantId, conversationId);
        if (conv == null) {
            throw new IllegalArgumentException("conversation not found");
        }
        if (!CHANNEL.equals(conv.getChannel()) || !BIZ_REF_TYPE.equals(conv.getBizRefType())
                || !workitemId.equals(conv.getBizRefId())) {
            throw new IllegalArgumentException("conversation does not belong to this workitem");
        }
    }

    public List<ClarificationConversationVO> listConversations(Long tenantId, Long workitemId, Long agentId) {
        List<AgentConversationDO> conversations = convDao.listByBizRef(
                tenantId, CHANNEL, BIZ_REF_TYPE, workitemId, agentId);
        return conversations.stream()
                .map(c -> toVO(c, null))
                .collect(Collectors.toList());
    }

    public ClarificationConversationVO getConversation(Long tenantId, Long workitemId, Long conversationId) {
        AgentConversationDO conv = convDao.findById(tenantId, conversationId);
        if (conv == null) {
            throw new IllegalArgumentException("conversation not found");
        }
        if (!CHANNEL.equals(conv.getChannel()) || !BIZ_REF_TYPE.equals(conv.getBizRefType())
                || !workitemId.equals(conv.getBizRefId())) {
            throw new IllegalArgumentException("conversation does not belong to this workitem");
        }
        List<AgentConversationTurnDO> turns = turnDao.listTurnsByConversation(tenantId, conversationId);
        List<ClarificationTurnVO> turnVOs = turns.stream()
                .map(t -> ClarificationTurnVO.builder()
                        .id(t.getId())
                        .direction(t.getDirection())
                        .content(t.getContent())
                        .status(t.getStatus())
                        .error(t.getError())
                        .gmtCreate(t.getGmtCreate())
                        .build())
                .collect(Collectors.toList());

        AgentConversationTurnDO processing = turnDao.findProcessingInbound(tenantId, conversationId);
        if (processing == null) {
            processing = turnDao.findNextQueuedInbound(tenantId, conversationId);
        }
        return toVO(conv, turnVOs, processing);
    }

    @Transactional
    public ClarificationConversationVO getOrCreateConversation(Long tenantId, Long workitemId, Long agentId) {
        AgentConversationDO existing = convDao.findLatestByBizRef(
                tenantId, CHANNEL, BIZ_REF_TYPE, workitemId, agentId);
        if (existing != null) {
            return getConversation(tenantId, workitemId, existing.getId());
        }
        return createConversation(tenantId, workitemId, agentId);
    }

    @Transactional
    public ClarificationConversationVO createConversation(Long tenantId, Long workitemId, Long agentId) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("agent not found");
        }
        Long executorId = executorSelector.select(agentId, null);

        AgentConversationDO conv = new AgentConversationDO();
        conv.setTenantId(tenantId);
        conv.setAgentId(agentId);
        conv.setChannel(CHANNEL);
        conv.setBizRefType(BIZ_REF_TYPE);
        conv.setBizRefId(workitemId);
        conv.setChannelConversationId(UUID.randomUUID().toString());
        conv.setExecutorId(executorId);
        conv.setStatus("ACTIVE");
        conv.setLastTurnAt(new Date());
        convDao.insert(conv);

        return toVO(conv, List.of());
    }

    @Transactional
    public void submitTurn(Long tenantId, Long workitemId, Long conversationId,
            String content, String clientMessageId) {
        AgentConversationDO conv = convDao.findById(tenantId, conversationId);
        if (conv == null) {
            throw new IllegalArgumentException("conversation not found");
        }
        if (!CHANNEL.equals(conv.getChannel()) || !BIZ_REF_TYPE.equals(conv.getBizRefType())
                || !workitemId.equals(conv.getBizRefId())) {
            throw new IllegalArgumentException("conversation does not belong to this workitem");
        }
        Long executorId = executorSelector.select(conv.getAgentId(), conv.getExecutorId());
        if (executorId == null) {
            throw new IllegalStateException("RUNTIME_OFFLINE");
        }
        if (!executorId.equals(conv.getExecutorId())) {
            convDao.updateExecutor(tenantId, conversationId, executorId);
        }
        String externalMsgId = "web-clarification:" + clientMessageId;
        conversationService.submitTurn(tenantId, conv.getAgentId(), CHANNEL,
                conv.getChannelConversationId(), content, externalMsgId);
    }

    public void cancelTurn(Long tenantId, Long workitemId, Long conversationId, Long turnId) {
        AgentConversationDO conv = convDao.findById(tenantId, conversationId);
        if (conv == null) {
            throw new IllegalArgumentException("conversation not found");
        }
        if (!CHANNEL.equals(conv.getChannel()) || !BIZ_REF_TYPE.equals(conv.getBizRefType())
                || !workitemId.equals(conv.getBizRefId())) {
            throw new IllegalArgumentException("conversation does not belong to this workitem");
        }
        conversationService.requestTurnCancel(tenantId, conversationId, turnId);
    }

    private ClarificationConversationVO toVO(AgentConversationDO conv, List<ClarificationTurnVO> turns) {
        return toVO(conv, turns, null);
    }

    private ClarificationConversationVO toVO(AgentConversationDO conv, List<ClarificationTurnVO> turns,
            AgentConversationTurnDO processing) {
        boolean executorOnline = conv.getExecutorId() != null
                && runtimePresence != null
                && runtimePresence.isExecutorOnline(conv.getExecutorId());
        boolean streamingSupported = executorOnline
                && runtimePresence != null
                && runtimePresence.supportsProtocolFeature(conv.getExecutorId(), "CONVERSATION_TURN_EVENT");
        boolean cancelSupported = executorOnline
                && runtimePresence != null
                && runtimePresence.supportsProtocolFeature(conv.getExecutorId(), "CONVERSATION_TURN_CANCEL");
        AgentDO agent = agentDao.findById(conv.getAgentId());
        return ClarificationConversationVO.builder()
                .id(conv.getId())
                .agentId(conv.getAgentId())
                .agentName(agent != null ? agent.getName() : null)
                .channelConversationId(conv.getChannelConversationId())
                .status(conv.getStatus())
                .executorOnline(executorOnline)
                .streamingSupported(streamingSupported)
                .cancelSupported(cancelSupported)
                .cliSessionRef(conv.getCliSessionRef())
                .processingStatus(processing != null ? processing.getStatus() : null)
                .processingTurnId(processing != null ? processing.getId() : null)
                .lastTurnAt(conv.getLastTurnAt())
                .gmtCreate(conv.getGmtCreate())
                .turns(turns)
                .build();
    }
}
