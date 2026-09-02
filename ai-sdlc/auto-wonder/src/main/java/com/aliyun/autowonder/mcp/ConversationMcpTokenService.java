package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationDao;
import org.springframework.stereotype.Service;

import java.util.Map;

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

    public String issue(AgentConversationDO conversation, long userId) {
        if (conversation == null || conversation.getId() == null || conversation.getTenantId() == null
                || userId <= 0) {
            throw new IllegalArgumentException("conversation MCP identity is incomplete");
        }
        return PREFIX + jwtService.signScoped(userId, conversation.getTenantId(), PURPOSE,
                conversation.getId(), TTL_SECONDS);
    }

    public McpAccessTokenService.Principal authenticate(String token) {
        try {
            if (token == null || !token.startsWith(PREFIX)) {
                throw new IllegalArgumentException("invalid prefix");
            }
            Map<String, Object> claims = jwtService.parseScoped(token.substring(PREFIX.length()));
            if (!PURPOSE.equals(claims.get("purpose"))) {
                throw new IllegalArgumentException("invalid purpose");
            }
            long conversationId = ((Number) claims.get("subjectId")).longValue();
            long workspaceId = ((Number) claims.get("workspace")).longValue();
            AgentConversationDO conversation = conversationDao.findById(workspaceId, conversationId);
            if (conversation == null || !"ACTIVE".equals(conversation.getStatus())) {
                throw new IllegalArgumentException("conversation is inactive");
            }
            long userId = ((Number) claims.get("uid")).longValue();
            return new McpAccessTokenService.Principal(
                    workspaceId, userId, conversationId, WorkspaceAccessLevel.READ_WRITE,
                    McpAccessTokenService.CredentialType.CONVERSATION);
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }
}
