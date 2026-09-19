package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.conversation.dto.ClarificationConversationVO;
import com.aliyun.autowonder.conversation.dto.ClarificationElicitationVO;
import com.aliyun.autowonder.conversation.dto.ClarificationSlashCommandVO;
import com.aliyun.autowonder.conversation.dto.ClarificationTurnVO;
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
    private final ConversationRuntimePresence runtimePresence;
    private final ConversationElicitationService elicitationService;
    private final ConversationCommandsService commandsService;
    private final ConversationExecutorRouter executorRouter;

    public WorkitemClarificationConversationService(AgentConversationDao convDao,
            AgentConversationTurnDao turnDao, AgentConversationService conversationService,
            AgentDao agentDao,
            ConversationRuntimePresence runtimePresence,
            ConversationElicitationService elicitationService,
            ConversationCommandsService commandsService,
            ConversationExecutorRouter executorRouter) {
        this.convDao = convDao;
        this.turnDao = turnDao;
        this.conversationService = conversationService;
        this.agentDao = agentDao;
        this.runtimePresence = runtimePresence;
        this.elicitationService = elicitationService;
        this.commandsService = commandsService;
        this.executorRouter = executorRouter;
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
        // 历史正文是详情接口的核心价值，读取失败必须显式抛错：
        // 降级成空 turns 会把「读取失败」伪装成「没有历史」（工单 55411 缺陷一形态）。
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
        // 仅详情接口查挂起卡片与命令快照：列表页不需要，逐会话查会白跑 N 次（快照还是 Redis 读）。
        // 两者都是辅助信息：失败降级为空并留诊断日志，不阻断已有历史返回（修复要求 3）。
        return toVO(conv, turnVOs, processing,
                pendingElicitationsQuietly(tenantId, conversationId),
                commandsSnapshotQuietly(tenantId, conversationId));
    }

    /** 挂起卡片是辅助信息：失败降级为空列表，已有历史仍正常返回。 */
    private List<ClarificationElicitationVO> pendingElicitationsQuietly(Long tenantId, Long conversationId) {
        try {
            return pendingElicitationVOs(tenantId, conversationId);
        } catch (RuntimeException e) {
            log.warn("pending elicitations degraded tenantId={} conversationId={}: {}", tenantId,
                    conversationId, e.getMessage());
            return List.of();
        }
    }

    /** 命令快照（Redis 种子）是辅助信息：失败降级为空列表，已有历史仍正常返回。 */
    private List<ClarificationSlashCommandVO> commandsSnapshotQuietly(Long tenantId, Long conversationId) {
        try {
            return commandsService.snapshot(tenantId, conversationId);
        } catch (RuntimeException e) {
            log.warn("commands snapshot degraded tenantId={} conversationId={}: {}", tenantId,
                    conversationId, e.getMessage());
            return List.of();
        }
    }

    private List<ClarificationElicitationVO> pendingElicitationVOs(Long tenantId, Long conversationId) {
        return elicitationService.listPending(tenantId, conversationId).stream()
                .map(record -> ClarificationElicitationVO.builder()
                        .requestId(record.getRequestId())
                        .turnId(record.getTurnId())
                        .mode(record.getMode())
                        .message(record.getMessage())
                        .requestedSchema(record.getSchemaJson())
                        .status(record.getStatus())
                        .gmtCreate(record.getGmtCreate())
                        .build())
                .collect(Collectors.toList());
    }

    /** 提交问答卡片的回答。归属校验先于卡片操作，避免跨工单猜 conversationId。 */
    public void replyElicitation(Long tenantId, Long workitemId, Long conversationId,
            String requestId, String action, String content) {
        verifyConversationBelongsToWorkitem(tenantId, workitemId, conversationId);
        elicitationService.reply(tenantId, conversationId, requestId, action, content);
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
        Long executorId = executorRouter.select(tenantId, agentId,
                requireOnlineVersion(agent), null);

        AgentConversationDO conv = new AgentConversationDO();
        conv.setTenantId(tenantId);
        conv.setAgentId(agentId);
        conv.setAgentVersionId(agent.getOnlineVersionId());
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
        AgentDO agent = agentDao.findById(conv.getAgentId());
        Long executorId = executorRouter.select(tenantId, conv.getAgentId(),
                requireOnlineVersion(agent), conv.getExecutorId());
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

    private Long requireOnlineVersion(AgentDO agent) {
        if (agent == null || agent.getOnlineVersionId() == null) {
            throw new IllegalStateException("agent has no online version");
        }
        return agent.getOnlineVersionId();
    }

    private ClarificationConversationVO toVO(AgentConversationDO conv, List<ClarificationTurnVO> turns) {
        return toVO(conv, turns, null, List.of(), List.of());
    }

    private ClarificationConversationVO toVO(AgentConversationDO conv, List<ClarificationTurnVO> turns,
            AgentConversationTurnDO processing, List<ClarificationElicitationVO> pendingElicitations,
            List<ClarificationSlashCommandVO> availableCommands) {
        boolean executorOnline = executorOnlineQuietly(conv.getExecutorId());
        boolean streamingSupported = executorOnline
                && protocolFeatureQuietly(conv.getExecutorId(), "CONVERSATION_TURN_EVENT");
        boolean cancelSupported = executorOnline
                && protocolFeatureQuietly(conv.getExecutorId(), "CONVERSATION_TURN_CANCEL");
        // 离线时卡片提交必然失败（回答要下发帧），所以入口也不该亮出来。
        boolean acpInteractionSupported = executorOnline
                && protocolFeatureQuietly(conv.getExecutorId(),
                        "CONVERSATION_ACP_INTERACTION_V1");
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
                .acpInteractionSupported(acpInteractionSupported)
                .cliSessionRef(conv.getCliSessionRef())
                .processingStatus(processing != null ? processing.getStatus() : null)
                .processingTurnId(processing != null ? processing.getId() : null)
                .lastTurnAt(conv.getLastTurnAt())
                .gmtCreate(conv.getGmtCreate())
                .turns(turns)
                .pendingElicitations(pendingElicitations)
                .availableCommands(availableCommands)
                .build();
    }

    /** 在线状态是辅助能力：查询失败按离线降级并留诊断日志，不阻断历史返回。 */
    private boolean executorOnlineQuietly(Long executorId) {
        if (executorId == null || runtimePresence == null) {
            return false;
        }
        try {
            return runtimePresence.isExecutorOnline(executorId);
        } catch (RuntimeException e) {
            log.warn("executor presence degraded executorId={}: {}", executorId, e.getMessage());
            return false;
        }
    }

    /** 协议能力协商同属辅助信息：失败按不支持降级，不阻断历史返回。 */
    private boolean protocolFeatureQuietly(Long executorId, String feature) {
        if (executorId == null || runtimePresence == null) {
            return false;
        }
        try {
            return runtimePresence.supportsProtocolFeature(executorId, feature);
        } catch (RuntimeException e) {
            log.warn("protocol feature degraded executorId={} feature={}: {}", executorId, feature,
                    e.getMessage());
            return false;
        }
    }
}
