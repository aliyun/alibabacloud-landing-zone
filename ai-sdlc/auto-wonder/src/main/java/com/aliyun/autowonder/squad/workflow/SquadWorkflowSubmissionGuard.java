package com.aliyun.autowonder.squad.workflow;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationDao;
import com.aliyun.autowonder.mcp.McpAccessTokenService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * The single entry point of the workflow submission tool. A conversation credential may only
 * submit for its own workflow conversation; the snapshot's conversation binding is checked again
 * by the service so a token from another dispatch attempt cannot land a result.
 */
@Component
public class SquadWorkflowSubmissionGuard {

    public static final String TOOL_NAME = "autowonder.submit_squad_workflow";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final int MAX_PAYLOAD_LENGTH = 1024 * 1024;

    private final AgentConversationDao conversationDao;
    private final SquadWorkflowService workflowService;

    public SquadWorkflowSubmissionGuard(AgentConversationDao conversationDao,
            SquadWorkflowService workflowService) {
        this.conversationDao = conversationDao;
        this.workflowService = workflowService;
    }

    public boolean isWorkflowConversation(Long workspaceId, Long conversationId) {
        if (workspaceId == null || conversationId == null) {
            return false;
        }
        AgentConversationDO conversation = conversationDao.findById(workspaceId, conversationId);
        return conversation != null
                && SquadWorkflowChannelSink.CHANNEL.equals(conversation.getChannel())
                && STATUS_ACTIVE.equals(conversation.getStatus());
    }

    public WorkflowFeedback submit(McpAccessTokenService.Principal principal, Map<String, Object> args) {
        if (principal == null || principal.workspaceId() == null
                || principal.credentialType() != McpAccessTokenService.CredentialType.CONVERSATION) {
            throw new BizException(ErrorCode.SQUAD_WORKFLOW_NOT_AVAILABLE);
        }
        long conversationId = principal.tokenId();
        AgentConversationDO conversation = conversationDao.findById(principal.workspaceId(), conversationId);
        if (conversation == null || !SquadWorkflowChannelSink.CHANNEL.equals(conversation.getChannel())
                || !STATUS_ACTIVE.equals(conversation.getStatus())
                || !Objects.equals(conversation.getOwnerUserId(), principal.userId())) {
            throw new BizException(ErrorCode.SQUAD_WORKFLOW_NOT_AVAILABLE);
        }
        String generationId = required(args, "generationId");
        String attemptId = required(args, "attemptId");
        String submissionId = required(args, "submissionId");
        String payloadJson = required(args, "payloadJson");
        if (payloadJson.length() > MAX_PAYLOAD_LENGTH) {
            throw new BizException(ErrorCode.SQUAD_WORKFLOW_INPUT_TOO_LARGE);
        }
        return workflowService.acceptSubmission(principal.workspaceId(), conversationId,
                generationId, attemptId, submissionId, payloadJson);
    }

    private String required(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new BizException(ErrorCode.SQUAD_WORKFLOW_INVALID_REQUEST);
        }
        return text;
    }
}
