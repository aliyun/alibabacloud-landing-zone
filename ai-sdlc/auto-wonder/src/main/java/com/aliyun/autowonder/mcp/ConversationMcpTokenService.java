package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationDao;
import com.aliyun.autowonder.conversation.PlatformConversationChannel;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class ConversationMcpTokenService {
    static final String PREFIX = "awconversation_";
    private static final String PURPOSE = "conversation-mcp";
    private static final long TTL_SECONDS = 24 * 60 * 60;

    private final JwtService jwtService;
    private final AgentConversationDao conversationDao;

    public ConversationMcpTokenService(JwtService jwtService, AgentConversationDao conversationDao) {
        this.jwtService = jwtService;
        this.conversationDao = conversationDao;
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
        if (PlatformConversationChannel.CHANNEL.equals(conversation.getChannel())
                && !Objects.equals(conversation.getOwnerUserId(), userId)) {
            // 平台会话的 Owner 不可变更，签给任何其他人都是身份冒用。
            throw new IllegalArgumentException("conversation MCP identity does not match the owner");
        }
        return PREFIX + jwtService.signConversation(userId, conversation.getTenantId(), PURPOSE,
                conversation.getId(), conversation.getAgentId(), conversation.getAgentVersionId(),
                TTL_SECONDS);
    }

    public McpAccessTokenService.Principal authenticate(String token) {
        try {
            if (token == null || !token.startsWith(PREFIX)) {
                throw new IllegalArgumentException("invalid prefix");
            }
            JwtService.ConversationClaims claims =
                    jwtService.parseConversation(token.substring(PREFIX.length()));
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
            if (PlatformConversationChannel.CHANNEL.equals(conversation.getChannel())) {
                Long owner = conversation.getOwnerUserId();
                if (owner == null || owner != claims.userId()) {
                    // Owner 缺失或不相等都必须拒绝：跨用户重放令牌不能拿到任何权限。
                    throw new IllegalArgumentException("token owner does not match the conversation owner");
                }
            }
            return new McpAccessTokenService.Principal(
                    claims.tenantId(), claims.userId(), claims.conversationId(),
                    ceilingFor(conversation),
                    McpAccessTokenService.CredentialType.CONVERSATION);
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }

    /**
     * 平台管家会话的令牌只有只读上限：通用写工具在权限闸门处就走不通，写操作只能经
     * Owner 显式确认的行动计划通路，令牌级别再高也抬不动。
     * 遗留澄清渠道要能上传确认稿并推进工单状态，读写上限保持不变。
     */
    private static WorkspaceAccessLevel ceilingFor(AgentConversationDO conversation) {
        return PlatformConversationChannel.CHANNEL.equals(conversation.getChannel())
                ? WorkspaceAccessLevel.READ_ONLY
                : WorkspaceAccessLevel.READ_WRITE;
    }
}
