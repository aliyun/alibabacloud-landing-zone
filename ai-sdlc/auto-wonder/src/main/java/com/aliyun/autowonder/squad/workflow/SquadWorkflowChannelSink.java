package com.aliyun.autowonder.squad.workflow;

import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.ConversationChannelSink;
import org.springframework.stereotype.Component;

/**
 * The workflow conversation has no human-facing transport: the Chief reports progress exclusively
 * through the submit tool. The terminal callback is the only thing this sink needs, so a turn that
 * ends without an accepted submission fails its snapshot immediately instead of waiting for the
 * deadline.
 */
@Component
public class SquadWorkflowChannelSink implements ConversationChannelSink {

    public static final String CHANNEL = "SQUAD_WORKFLOW";

    private final SquadWorkflowFinalizer finalizer;

    public SquadWorkflowChannelSink(SquadWorkflowFinalizer finalizer) {
        this.finalizer = finalizer;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public void deliverReply(AgentConversationDO conv, String replyMarkdown, String sourceExternalMsgId) {
        // 无外部渠道可投递；回复内容只留在会话记录里供排障。
    }

    @Override
    public void turnTerminal(AgentConversationDO conv, String status, String replyMarkdown) {
        turnTerminal(conv, status, replyMarkdown, null);
    }

    @Override
    public void turnTerminal(AgentConversationDO conv, String status, String replyMarkdown,
            String error) {
        if (conv == null || conv.getId() == null || conv.getTenantId() == null) {
            return;
        }
        finalizer.conversationTerminal(conv.getTenantId(), conv.getId(), status, error);
    }
}
