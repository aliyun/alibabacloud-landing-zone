package com.aliyun.autowonder.conversation;

public interface ConversationChannelSink {
    /** 渠道标识,如 "DINGTALK"。 */
    String channel();

    void deliverReply(AgentConversationDO conv, String replyMarkdown, String sourceExternalMsgId);

    /**
     * Notification that the turn reached a terminal status. Only channels that need to converge an
     * external task override this; message channels keep their existing behaviour untouched.
     */
    default void turnTerminal(AgentConversationDO conv, String status, String replyMarkdown) {
    }

    /** Optional diagnostic from the executor; older sinks retain their existing callback. */
    default void turnTerminal(AgentConversationDO conv, String status, String replyMarkdown,
            String error) {
        turnTerminal(conv, status, replyMarkdown);
    }
}
