package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationDao;
import com.aliyun.autowonder.conversation.PlatformConversationChannel;
import com.aliyun.autowonder.squad.workflow.SquadWorkflowChannelSink;
import org.springframework.stereotype.Service;

import java.util.Objects;
import com.aliyun.autowonder.conversation.AgentConversationTurnDao;
import com.aliyun.autowonder.conversation.AgentConversationTurnDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.user.UserDO;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class ConversationMcpTokenService {
    static final String PREFIX = "awconversation_";
    public static final String TURN_PURPOSE = "conversation-turn-mcp";
    private static final String PURPOSE = "conversation-mcp";
    private static final long TTL_SECONDS = 24 * 60 * 60;

    private final JwtService jwtService;
    private final AgentConversationDao conversationDao;

    private final AgentConversationTurnDao turnDao;
    private final UserDao userDao;
    @Autowired(required = false)
    private ConversationCredentialAuditService executionAudit;

    public ConversationMcpTokenService(JwtService jwtService, AgentConversationDao conversationDao) {
        this(jwtService, conversationDao, null, null);
    }

    @Autowired
    public ConversationMcpTokenService(JwtService jwtService, AgentConversationDao conversationDao,
            AgentConversationTurnDao turnDao, UserDao userDao) {
        this.jwtService = jwtService;
        this.conversationDao = conversationDao;
        this.turnDao = turnDao;
        this.userDao = userDao;
    }

    /**
     * 签出的令牌代表 conversation 的归属人本人，而不是 Agent 的创建者或修改者；
     * agent / agentVersion 一起写进声明，会话切版本后旧令牌立刻失效。
     */
    public String issue(AgentConversationDO conversation, long userId) {
        if (conversation == null || conversation.getId() == null || conversation.getTenantId() == null
                || conversation.getAgentId() == null || conversation.getAgentVersionId() == null
                || userId <= 0) {
            throw new IllegalArgumentException("conversation MCP identity is incomplete");
        }
        if (isOwnerBound(conversation)
                && !Objects.equals(conversation.getOwnerUserId(), userId)) {
            // 平台会话与工作流会话的 Owner 不可变更，签给任何其他人都是身份冒用。
            throw new IllegalArgumentException("conversation MCP identity does not match the owner");
        }
        return PREFIX + jwtService.signConversation(userId, conversation.getTenantId(), PURPOSE,
                conversation.getId(), conversation.getAgentId(), conversation.getAgentVersionId(),
                TTL_SECONDS);
    }

    public McpAccessTokenService.Principal authenticate(String token) {
        McpAccessTokenService.Principal signedIdentity = null;
        try {
            if (token == null || !token.startsWith(PREFIX)) {
                throw new IllegalArgumentException("invalid prefix");
            }
            JwtService.ConversationClaims claims =
                    jwtService.parseConversation(token.substring(PREFIX.length()));
            if (TURN_PURPOSE.equals(claims.purpose())) {
                var turnClaims = jwtService.parseConversationTurn(token.substring(PREFIX.length()));
                signedIdentity = new McpAccessTokenService.Principal(turnClaims.workspaceId(), turnClaims.userId(),
                        turnClaims.scope().conversationId(), WorkspaceAccessLevel.READ_ONLY,
                        McpAccessTokenService.CredentialType.CONVERSATION, turnClaims.scope());
                return validateExecution(turnClaims.userId(), turnClaims.workspaceId(), turnClaims.scope());
            }
            if (!PURPOSE.equals(claims.purpose())) {
                throw new IllegalArgumentException("invalid purpose");
            }
            AgentConversationDO conversation =
                    conversationDao.findById(claims.tenantId(), claims.conversationId());
            if (conversation == null || !"ACTIVE".equals(conversation.getStatus())) {
                throw new IllegalArgumentException("conversation is inactive");
            }
            if (!Objects.equals(conversation.getAgentId(), claims.agentId())
                    || !Objects.equals(conversation.getAgentVersionId(), claims.agentVersionId())) {
                throw new IllegalArgumentException("conversation agent binding changed");
            }
            if (isOwnerBound(conversation)) {
                Long owner = conversation.getOwnerUserId();
                if (owner == null || owner != claims.userId()) {
                    // Owner 缺失或不相等都必须拒绝：跨用户重放令牌不能拿到任何权限。
                    throw new IllegalArgumentException("token owner does not match the conversation owner");
                }
            }
            if ("WORKITEM_CLARIFICATION".equals(conversation.getChannel())) {
                AgentConversationTurnDO current = turnDao == null ? null
                        : turnDao.findProcessingInbound(claims.tenantId(), claims.conversationId());
                if (current == null || current.getSubmittedUserId() != null
                        || !"IN".equals(current.getDirection()) || !"PROCESSING".equals(current.getStatus())
                        || !Objects.equals(current.getTenantId(), claims.tenantId())
                        || !Objects.equals(current.getConversationId(), claims.conversationId())) {
                    throw new IllegalArgumentException("legacy clarification turn is unavailable");
                }
            }
            return new McpAccessTokenService.Principal(
                    claims.tenantId(), claims.userId(), claims.conversationId(),
                    ceilingFor(conversation),
                    McpAccessTokenService.CredentialType.CONVERSATION);
        } catch (Exception e) {
            if (executionAudit != null) executionAudit.record(signedIdentity, "MCP_AUTHENTICATE", null, e);
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }

    public String issueForTurn(AgentConversationDO conversation, Long turnId) {
        if (conversation == null || turnId == null || turnDao == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        AgentConversationTurnDO turn = turnDao.findByConversationTurn(conversation.getTenantId(), conversation.getId(), turnId);
        if (turn == null || turn.getSubmittedUserId() == null || turn.getSubmittedUserId() <= 0) {
            throw BizException.keyed(ErrorCode.NO_PERMISSION, "conversation.actor_unknown", null,
                    "conversation turn sender is unavailable; send a new message");
        }
        if (conversation.getExecutorId() == null || conversation.getAgentId() == null
                || conversation.getAgentVersionId() == null || turn.getDispatchAttempt() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        var scope = new McpAccessTokenService.ExecutionScope(conversation.getId(), turnId,
                turn.getDispatchAttempt(), conversation.getExecutorId(), conversation.getAgentId(), conversation.getAgentVersionId());
        validateExecution(turn.getSubmittedUserId(), conversation.getTenantId(), scope);
        return PREFIX + jwtService.signConversationTurn(turn.getSubmittedUserId(), conversation.getTenantId(),
                TURN_PURPOSE, scope, null, TTL_SECONDS);
    }

    /** Network credential boundary only; trusted internal principals need no active turn. */
    public McpAccessTokenService.Principal validateExecution(long userId, long workspaceId,
            McpAccessTokenService.ExecutionScope scope) {
        if (userId <= 0 || workspaceId <= 0 || scope == null || scope.turnId() <= 0
                || scope.conversationId() <= 0 || scope.dispatchAttempt() <= 0 || scope.executorId() <= 0
                || scope.agentId() <= 0 || scope.agentVersionId() <= 0 || turnDao == null || userDao == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        AgentConversationDO conversation = conversationDao.findById(workspaceId, scope.conversationId());
        AgentConversationTurnDO turn = turnDao.findByConversationTurn(workspaceId, scope.conversationId(), scope.turnId());
        UserDO user = userDao.findById(userId);
        if (user == null || Integer.valueOf(1).equals(user.getIsDeleted())
                || (Integer.valueOf(1).equals(user.getStatus())
                    && (user.getPasswordHash() == null || "DEACTIVATED".equals(user.getPasswordHash())))
                || conversation == null || !"ACTIVE".equals(conversation.getStatus())
                || !Objects.equals(conversation.getTenantId(), workspaceId)
                || !Objects.equals(conversation.getId(), scope.conversationId())
                || !Objects.equals(conversation.getExecutorId(), scope.executorId())
                || !Objects.equals(conversation.getAgentId(), scope.agentId())
                || !Objects.equals(conversation.getAgentVersionId(), scope.agentVersionId())
                || turn == null || !"IN".equals(turn.getDirection()) || !"PROCESSING".equals(turn.getStatus())
                || !Objects.equals(turn.getId(), scope.turnId())
                || !Objects.equals(turn.getTenantId(), workspaceId)
                || !Objects.equals(turn.getConversationId(), scope.conversationId())
                || !Objects.equals(turn.getSubmittedUserId(), userId)
                || !Objects.equals(turn.getDispatchAttempt(), scope.dispatchAttempt())
                || (isOwnerBound(conversation) && !Objects.equals(conversation.getOwnerUserId(), userId))) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return new McpAccessTokenService.Principal(workspaceId, userId, scope.conversationId(),
                ceilingFor(conversation), McpAccessTokenService.CredentialType.CONVERSATION, scope);
    }

    /**
     * 平台管家会话的令牌只有只读上限：通用写工具在权限闸门处就走不通，写操作只能经
     * Owner 显式确认的行动计划通路，令牌级别再高也抬不动。
     * 遗留澄清渠道要能上传确认稿并推进工单状态，读写上限保持不变。
     */
    private static WorkspaceAccessLevel ceilingFor(AgentConversationDO conversation) {
        return isOwnerBound(conversation)
                ? WorkspaceAccessLevel.READ_ONLY
                : WorkspaceAccessLevel.READ_WRITE;
    }

    private static boolean isOwnerBound(AgentConversationDO conversation) {
        String channel = conversation.getChannel();
        return PlatformConversationChannel.CHANNEL.equals(channel)
                || SquadWorkflowChannelSink.CHANNEL.equals(channel);
    }
}
