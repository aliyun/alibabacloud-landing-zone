package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentService;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.conversation.dto.ClarificationConversationVO;
import com.aliyun.autowonder.conversation.dto.ClarificationLaunchVO;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 外部系统触发工单澄清的入口：创建（或复用）澄清会话并按需投递首条消息。
 * 只负责「起会话 + 首条消息」，不派发交付、不接管后续答疑。
 */
@Service
public class WorkitemClarificationLaunchService {

    private final WorkitemDao workitemDao;
    private final AgentService agentService;
    private final WorkitemClarificationConversationService conversationService;

    public WorkitemClarificationLaunchService(WorkitemDao workitemDao, AgentService agentService,
            WorkitemClarificationConversationService conversationService) {
        this.workitemDao = workitemDao;
        this.agentService = agentService;
        this.conversationService = conversationService;
    }

    /**
     * 工单与数字员工都必须属于调用方工作空间，避免跨空间猜 id 起会话。
     * 会话复用「该工单 + 该数字员工」最近一条，因此重试同一 clientMessageId 不会新建会话或重复投递。
     */
    public ClarificationLaunchVO start(long tenantId, long workitemId, long agentId, long userId,
            String content, String clientMessageId, List<Long> attachmentIds) {
        WorkitemDO workitem = workitemDao.findById(workitemId);
        if (workitem == null || !Objects.equals(workitem.getTenantId(), tenantId)) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        AgentVO agent = agentService.get(agentId, tenantId);
        if (agent.getOnlineVersionId() == null) {
            throw new BizException(ErrorCode.AGENT_NOT_ONLINE);
        }
        ClarificationConversationVO conversation =
                conversationService.getOrCreateConversation(tenantId, workitemId, agentId);
        boolean hasAttachments = attachmentIds != null && !attachmentIds.isEmpty();
        boolean turnSubmitted = hasAttachments || (content != null && !content.isBlank());
        if (turnSubmitted) {
            conversationService.submitTurn(tenantId, workitemId, conversation.getId(), userId,
                    content, clientMessageId, attachmentIds);
        }
        return ClarificationLaunchVO.builder()
                .conversationId(conversation.getId())
                .agentId(conversation.getAgentId())
                .agentName(conversation.getAgentName())
                .status(conversation.getStatus())
                .executorOnline(conversation.isExecutorOnline())
                .turnSubmitted(turnSubmitted)
                .build();
    }
}
