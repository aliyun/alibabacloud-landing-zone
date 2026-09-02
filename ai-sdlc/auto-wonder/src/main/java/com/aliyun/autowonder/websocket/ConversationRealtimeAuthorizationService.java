package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ConversationRealtimeAuthorizationService implements RealtimeChannelAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationRealtimeAuthorizationService.class);
    private static final String CONVERSATION_PREFIX = "conversation:";

    private final AgentConversationDao conversationDao;

    public ConversationRealtimeAuthorizationService(AgentConversationDao conversationDao) {
        this.conversationDao = conversationDao;
    }

    public boolean authorize(long workspaceId, long userId, String channel) {
        if (channel == null || !channel.startsWith(CONVERSATION_PREFIX)) {
            return false;
        }
        long conversationId;
        try {
            conversationId = Long.parseLong(channel.substring(CONVERSATION_PREFIX.length()));
        } catch (NumberFormatException e) {
            return false;
        }
        AgentConversationDO conv = conversationDao.findById(workspaceId, conversationId);
        if (conv == null) {
            log.warn("conversation subscription denied: not found workspaceId={} conversationId={}",
                    workspaceId, conversationId);
            return false;
        }
        return true;
    }

    @Override
    public boolean supports(String channel) { return channel != null && channel.startsWith(CONVERSATION_PREFIX); }

    public Long parseConversationId(String channel) {
        if (channel == null || !channel.startsWith(CONVERSATION_PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(channel.substring(CONVERSATION_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
